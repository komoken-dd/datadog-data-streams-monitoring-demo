package io.github.komokendd.dsm.brokerage.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.JdbcEventOutbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
class OrderSubmissionPostgresTest {
    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private RemoteReservations remote;

    @BeforeEach
    void prepare() {
        String port = System.getenv("BROKERAGE_TEST_POSTGRES_PORT");
        String user = System.getenv().getOrDefault("BROKERAGE_TEST_POSTGRES_USER", "audit_admin");
        var adminJdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + port + "/postgres", user, ""));
        if (adminJdbc.queryForObject("select count(*) from pg_database where datname='order_submissions_test'", Integer.class) == 0) {
            adminJdbc.execute("create database order_submissions_test");
        }
        var datasource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + port + "/order_submissions_test", user, "");
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("drop schema if exists order_service cascade");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__order_submissions.sql"),
                new ClassPathResource("db/migration/V2__bounded_demo_epoch.sql"),
                new ClassPathResource("db/migration/V3__outbox_trace_context.sql")).execute(datasource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        remote = new RemoteReservations();
    }

    @Test
    void reservationCommitWithLostHttpResponseIsRecoveredFromDatabaseAfterRestart() {
        remote.loseFirstReply = true;
        var pending = (OrderResult.PendingBody) service(NOW, normalOutbox()).submit(request(), "stable-key").body();
        assertThat(state()).isEqualTo("UNKNOWN");
        var restarted = service(NOW.plusSeconds(2), normalOutbox());
        var accepted = restarted.advance(pending.orderId(), false);
        assertThat(accepted.httpStatus()).isEqualTo(202);
        assertThat(state()).isEqualTo("ACCEPTED");
        assertThat(remote.reservationCalls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_service.kafka_outbox", Integer.class)).isEqualTo(1);
        assertThat(restarted.submit(request(), "stable-key").body()).isEqualTo(accepted.body());
        assertThat(remote.reservationCalls.get()).isEqualTo(1);
    }

    @Test
    void databaseFailureAfterRemoteReservationLeavesDurableOriginalIntentForLookupOnlyRecovery() {
        var failing = new JdbcEventOutbox(jdbc, "order_service") {
            @Override public void append(String id, String topic, String key, SpecificRecord event) {
                super.append(id, topic, key, event);
                throw new IllegalStateException("injected local failure before acceptance commit");
            }
        };
        assertThatThrownBy(() -> service(NOW, failing).submit(request(), "database-failure-key"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state()).isEqualTo("RESERVING");
        assertThat(jdbc.queryForObject("select count(*) from order_service.kafka_outbox", Integer.class)).isZero();
        String id = jdbc.queryForObject("select order_id from order_service.submissions", String.class);
        var recovered = service(NOW.plusSeconds(2), normalOutbox()).advance(id, false);
        assertThat(recovered.httpStatus()).isEqualTo(202);
        assertThat(remote.reservationCalls.get()).isEqualTo(1);
        assertThat(remote.cancellationCalls.get()).isZero();
    }

    @Test
    void cancellationFailureSurvivesRestartWithoutPublishingOrLosingAHold() {
        remote.loseFirstReply = true;
        var pending = (OrderResult.PendingBody) service(NOW, normalOutbox()).submit(request(), "cancel-key").body();
        remote.failCancellation = true;
        assertThat(service(NOW.plusSeconds(31), normalOutbox()).advance(pending.orderId(), false))
                .isInstanceOf(OrderResult.Pending.class);
        assertThat(remote.holds).containsKey(pending.orderId());
        remote.failCancellation = false;
        assertThat(service(NOW.plusSeconds(32), normalOutbox()).advance(pending.orderId(), false).httpStatus()).isEqualTo(409);
        assertThat(state()).isEqualTo("CANCELLED");
        assertThat(remote.holds).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from order_service.kafka_outbox", Integer.class)).isZero();
    }

    @Test
    void concurrentSameKeyRequestsCreateOneReservationAndOneImmutableEvent() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 10; round++) {
                String key = "concurrent-key-" + round;
                var ready = new java.util.concurrent.CountDownLatch(2);
                var start = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<OrderResult> submit = () -> {
                    ready.countDown();
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
                    return service(NOW, normalOutbox()).submit(request(), key);
                };
                var first = workers.submit(submit);
                var second = workers.submit(submit);
                assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(first.get().body()).isEqualTo(second.get().body());
            }
        }
        assertThat(remote.reservationCalls.get()).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from order_service.submissions", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from order_service.kafka_outbox", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select submission_count from order_service.epoch_budget", Integer.class)).isEqualTo(10);
    }

    @Test
    void unrelatedUniqueIdentityCollisionCannotLoadAnotherRequestOrConsumeEpochBudget() {
        service(NOW, normalOutbox()).submit(request(), "original-key");
        var store = new OrderSubmissionStore(jdbc);
        String id = jdbc.queryForObject("select order_id from order_service.submissions", String.class);
        var original = store.find(id).orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                store.createOrLoad("different-key", original.requestHash(), original.event(), original.deadline())))
                .isInstanceOf(OrderSubmissionStore.ConflictingRequestException.class);
        assertThat(jdbc.queryForObject("select count(*) from order_service.submissions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select submission_count from order_service.epoch_budget", Integer.class)).isEqualTo(1);
    }

    @Test
    void reusingAKeyWithDifferentTermsIsRejectedWithoutAnotherRemoteSideEffect() {
        service(NOW, normalOutbox()).submit(request(), "one-logical-order");
        var conflict = service(NOW.plusSeconds(1), normalOutbox()).submit(
                OrderValidatorTest.limit("D101", "BUY", 200, "2800"), "one-logical-order");
        assertThat(conflict.httpStatus()).isEqualTo(409);
        assertThat(((OrderResult.ErrorBody) conflict.body()).code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(remote.reservationCalls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select submission_count from order_service.epoch_budget", Long.class)).isEqualTo(1);
    }

    @Test
    void epochCapIsDurableAndIdempotentRetriesDoNotConsumeAdditionalBudget() {
        jdbc.update("update order_service.epoch_budget set max_submissions=1");
        var initial = service(NOW, normalOutbox()).submit(request(), "epoch-key");
        assertThat(initial.httpStatus()).isEqualTo(202);
        assertThat(service(NOW.plusSeconds(1), normalOutbox()).submit(request(), "epoch-key").body())
                .isEqualTo(initial.body());
        var blocked = service(NOW.plusSeconds(1), normalOutbox()).submit(request(), "new-key");
        assertThat(blocked.httpStatus()).isEqualTo(429);
        assertThat(((OrderResult.ErrorBody) blocked.body()).code()).isEqualTo("DEMO_EPOCH_LIMIT_REACHED");
        assertThat(remote.reservationCalls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select submission_count from order_service.epoch_budget", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentNewOrdersCannotExceedEpochBudget() throws Exception {
        jdbc.update("update order_service.epoch_budget set max_submissions=1");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> service(NOW, normalOutbox()).submit(request(), "cap-first"));
            var second = workers.submit(() -> service(NOW, normalOutbox()).submit(request(), "cap-second"));
            assertThat(java.util.List.of(first.get().httpStatus(), second.get().httpStatus()))
                    .containsExactlyInAnyOrder(202, 429);
        }
        assertThat(remote.reservationCalls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select submission_count from order_service.epoch_budget", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_service.submissions", Integer.class)).isEqualTo(1);
    }

    private String state() { return jdbc.queryForObject("select state from order_service.submissions", String.class); }
    private JdbcEventOutbox normalOutbox() { return new JdbcEventOutbox(jdbc,"order_service"); }
    private OrderIntakeService service(Instant now, JdbcEventOutbox outbox) {
        return new OrderIntakeService(remote, new SimpleMeterRegistry(), Clock.fixed(now, ZoneOffset.UTC),
                new OrderSubmissionStore(jdbc), outbox, transactions);
    }
    private CreateOrderRequest request() { return OrderValidatorTest.limit("D101","BUY",100,"2800"); }

    private static final class RemoteReservations implements BuyingPowerClient {
        final Map<String, BigDecimal> holds = new ConcurrentHashMap<>();
        final AtomicInteger reservationCalls = new AtomicInteger();
        final AtomicInteger cancellationCalls = new AtomicInteger();
        volatile boolean loseFirstReply;
        volatile boolean failCancellation;
        public ReservationOutcome reserve(BuyingPowerReservation.Request request) {
            reservationCalls.incrementAndGet();
            holds.putIfAbsent(request.orderId(), new BigDecimal("280000"));
            return loseFirstReply ? new Unavailable("response lost after remote commit") : lookup(request.orderId());
        }
        public ReservationOutcome lookup(String id) {
            return holds.containsKey(id) ? new Reserved(holds.get(id), BigDecimal.ZERO) : new Missing();
        }
        public boolean cancel(String id) {
            cancellationCalls.incrementAndGet();
            if (failCancellation) return false;
            holds.remove(id); return true;
        }
    }
}
