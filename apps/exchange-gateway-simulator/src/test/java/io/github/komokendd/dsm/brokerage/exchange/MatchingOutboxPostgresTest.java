package io.github.komokendd.dsm.brokerage.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.github.komokendd.dsm.brokerage.common.DurableKafkaSender;
import io.github.komokendd.dsm.brokerage.common.JdbcEventOutbox;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.common.AvroSpecificDeserializer;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "BROKERAGE_TEST_POSTGRES_PORT", matches = "[0-9]+")
class MatchingOutboxPostgresTest {
    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private WorkingOrderRepository repository;
    private MatchingEngine engine;
    private DurableKafkaSender sender;

    @BeforeEach
    void prepare() {
        String port = System.getenv("BROKERAGE_TEST_POSTGRES_PORT");
        String user = System.getenv().getOrDefault("BROKERAGE_TEST_POSTGRES_USER", "audit_admin");
        var adminJdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + port + "/postgres", user, ""));
        if (adminJdbc.queryForObject("select count(*) from pg_database where datname='exchange_outbox_test'", Integer.class) == 0) {
            adminJdbc.execute("create database exchange_outbox_test");
        }
        var datasource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + port + "/exchange_outbox_test", user, "");
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("drop schema if exists exchange_gateway cascade");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__exchange_gateway.sql"),
                new ClassPathResource("db/migration/V2__immutable_matching_outbox.sql"),
                new ClassPathResource("db/migration/V3__outbox_trace_context.sql")).execute(datasource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        repository = new WorkingOrderRepository(jdbc);
        engine = new MatchingEngine(new Random(20261122), new MatchingSettings(1,0,4000,2,3,java.time.Duration.ofMinutes(10)));
        sender = mock(DurableKafkaSender.class);
        transactions.executeWithoutResult(status -> {
            repository.insert(Orders.market("first", "D101", Side.BUY, 100, NOW.minusSeconds(1)));
            repository.insert(Orders.market("second", "D101", Side.BUY, 100, NOW.minusSeconds(1)));
        });
    }

    @Test
    void failingBeforeBookCommitPublishesNothingAndRollsBackEveryStagedEvent() {
        var failing = new JdbcEventOutbox(jdbc, "exchange_gateway") {
            int appended;
            @Override public void append(String id, String topic, String key, SpecificRecord event) {
                super.append(id, topic, key, event);
                if (++appended == 2) throw new IllegalStateException("injected database failure before commit");
            }
        };
        scheduler(failing).matchingTick();
        assertThat(jdbc.queryForObject("select count(*) from exchange_gateway.kafka_outbox", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select sum(cumulative_quantity) from exchange_gateway.working_orders", Long.class)).isZero();
        verifyNoInteractions(sender);
        scheduler(new JdbcEventOutbox(jdbc, "exchange_gateway")).matchingTick();
        assertThat(jdbc.queryForObject("select count(*) from exchange_gateway.kafka_outbox", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select sum(cumulative_quantity) from exchange_gateway.working_orders", Long.class)).isEqualTo(200);
    }

    @Test
    void databaseFailureAfterBrokerAcknowledgmentsReplaysIdenticalCommittedPayloadsWithoutRematching() {
        var outbox = new JdbcEventOutbox(jdbc, "exchange_gateway");
        scheduler(outbox).matchingTick();
        List<byte[]> committed = jdbc.query("select payload from exchange_gateway.kafka_outbox order by id",
                (rs, row) -> rs.getBytes(1));
        List<byte[]> sent = new ArrayList<>();
        doAnswer(invocation -> { sent.add(((byte[]) invocation.getArgument(2)).clone()); return null; })
                .when(sender).send(anyString(), anyString(), any(byte[].class));
        var failCommit = new JdbcEventOutbox(jdbc, "exchange_gateway") {
            @Override public int relay(DurableKafkaSender sender, int limit) {
                super.relay(sender, limit);
                throw new IllegalStateException("injected failure after send, before database commit");
            }
        };
        scheduler(failCommit).relayOutbox();
        assertThat(jdbc.queryForObject("select count(*) from exchange_gateway.kafka_outbox where published_at is not null", Integer.class)).isZero();
        scheduler(outbox).matchingTick(); // completed orders cannot be matched again
        scheduler(outbox).relayOutbox();
        assertThat(sent).hasSize(4);
        assertThat(sent.get(0)).containsExactly(committed.get(0));
        assertThat(sent.get(2)).containsExactly(committed.get(0));
        assertThat(sent.get(1)).containsExactly(committed.get(1));
        assertThat(sent.get(3)).containsExactly(committed.get(1));
        assertThat(jdbc.queryForObject("select count(*) from exchange_gateway.kafka_outbox", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from exchange_gateway.kafka_outbox where published_at is not null", Integer.class)).isEqualTo(2);
    }

    @Test
    void restartRestoresCommittedTraceParentAndDuplicateAppendCannotReplaceIt() {
        var original = SpanContext.create("11111111111111111111111111111111", "2222222222222222",
                TraceFlags.getSampled(), TraceState.builder().put("vendor", "original").build());
        var unrelated = SpanContext.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb",
                TraceFlags.getDefault(), TraceState.getDefault());
        var outbox = new JdbcEventOutbox(jdbc, "exchange_gateway");
        try (var scope = Context.root().with(Span.wrap(original)).makeCurrent()) {
            scheduler(outbox).matchingTick();
        }
        var first = jdbc.queryForMap("select * from exchange_gateway.kafka_outbox order by id limit 1");
        assertThat(first.get("traceparent")).isEqualTo("00-11111111111111111111111111111111-2222222222222222-01");
        assertThat(first.get("tracestate")).isEqualTo("vendor=original");
        byte[] committedBytes = (byte[]) first.get("payload");
        var event = new AvroSpecificDeserializer<>(ExecutionReportEvent.class)
                .deserialize((String) first.get("topic"), committedBytes);
        try (var scope = Context.root().with(Span.wrap(unrelated)).makeCurrent()) {
            transactions.executeWithoutResult(status -> outbox.append((String) first.get("event_id"),
                    (String) first.get("topic"), (String) first.get("record_key"), event));
        }
        assertThat(jdbc.queryForObject("select traceparent from exchange_gateway.kafka_outbox where id=?",
                String.class, first.get("id"))).isEqualTo(first.get("traceparent"));

        // A new relay instance under an unrelated scheduler context must pass
        // the committed remote parent to the actual OpenTelemetry span builder.
        var tracer = mock(Tracer.class);
        var builder = mock(SpanBuilder.class, RETURNS_SELF);
        when(tracer.spanBuilder("outbox.publish")).thenReturn(builder);
        when(builder.startSpan()).thenReturn(Span.getInvalid());
        List<SpanContext> parents = new ArrayList<>();
        doAnswer(call -> { parents.add(Span.fromContext(call.getArgument(0, Context.class)).getSpanContext()); return builder; })
                .when(builder).setParent(any(Context.class));
        try (var global = mockStatic(GlobalOpenTelemetry.class);
                var scope = Context.root().with(Span.wrap(unrelated)).makeCurrent()) {
            global.when(() -> GlobalOpenTelemetry.getTracer("brokerage-demo-outbox")).thenReturn(tracer);
            scheduler(new JdbcEventOutbox(jdbc, "exchange_gateway")).relayOutbox();
        }
        assertThat(parents).hasSize(2).allSatisfy(parent -> {
            assertThat(parent.getTraceId()).isEqualTo(original.getTraceId());
            assertThat(parent.getSpanId()).isEqualTo(original.getSpanId());
            assertThat(parent.isRemote()).isTrue();
        });
        verify(sender).send((String) first.get("topic"), (String) first.get("record_key"), committedBytes);
        assertThat(jdbc.queryForObject("select payload from exchange_gateway.kafka_outbox where id=?",
                byte[].class, first.get("id"))).containsExactly(committedBytes);
    }

    @Test
    void purgingDetailedBookRowsDoesNotForgetAcceptedOrderIdentity() {
        scheduler(new JdbcEventOutbox(jdbc,"exchange_gateway")).matchingTick();
        repository.purgeCompletedBefore(Instant.now().plusSeconds(1));
        assertThat(jdbc.queryForObject("select count(*) from exchange_gateway.working_orders", Integer.class)).isZero();
        Boolean inserted = transactions.execute(status -> repository.insert(
                Orders.market("first", "D101", Side.BUY, 100, NOW.minusSeconds(1))));
        assertThat(inserted).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from exchange_gateway.working_orders", Integer.class)).isZero();
    }

    private MatchingScheduler scheduler(JdbcEventOutbox outbox) {
        return new MatchingScheduler(engine, repository, outbox, sender, transactions,
                mock(ExchangeMetrics.class), new ExchangeProperties(20261122,250,5000,600,1,0,4000,60),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
