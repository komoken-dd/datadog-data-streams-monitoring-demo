package io.github.komokendd.dsm.brokerage.common;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL outbox. Append and relay must run inside the caller's database transaction. */
public class JdbcEventOutbox {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcEventOutbox.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");
    private final JdbcTemplate jdbc;
    private final String table;
    private final AvroSpecificSerializer serializer = new AvroSpecificSerializer();

    public JdbcEventOutbox(JdbcTemplate jdbc, String schema) {
        if (!IDENTIFIER.matcher(schema).matches()) throw new IllegalArgumentException("Unsafe outbox schema");
        this.jdbc = jdbc;
        table = schema + ".kafka_outbox";
    }

    public void append(String eventId, String topic, String key, SpecificRecord event) {
        requireTransaction();
        byte[] bytes = serializer.serialize(topic, event);
        var trace = OutboxTraceContext.capture();
        int inserted = jdbc.update("insert into " + table
                        + " (event_id, topic, record_key, order_id, event_type, symbol, demo_scenario_id, payload, traceparent, tracestate, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) on conflict (event_id) do nothing",
                eventId, topic, key, text(event, "orderId"), text(event, "eventType"), text(event, "symbol"),
                text(event, "demoScenarioId"), bytes, trace.traceparent(), trace.tracestate());
        if (inserted == 0) {
            Row existing = jdbc.queryForObject("select * from "
                            + table + " where event_id = ?", JdbcEventOutbox::row, eventId);
            if (existing == null || !topic.equals(existing.topic()) || !key.equals(existing.key())
                    || !Arrays.equals(bytes, existing.payload())) {
                throw new IllegalStateException("An event ID cannot describe two outbox payloads");
            }
        }
    }

    /** A transaction-scoped publisher lock preserves ordering across overlapping replicas. */
    public int relay(DurableKafkaSender sender, int limit) {
        requireTransaction();
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid outbox batch limit");
        Boolean acquired = jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                Boolean.class, table + ":publisher");
        if (!Boolean.TRUE.equals(acquired)) return 0;
        List<Row> rows = jdbc.query("select * from " + table
                + " where published_at is null order by id limit ? for update", JdbcEventOutbox::row, limit);
        long budgetEndsAt = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        int published = 0;
        for (Row row : rows) {
            if (System.nanoTime() > budgetEndsAt) break;
            var span = GlobalOpenTelemetry.getTracer("brokerage-demo-outbox").spanBuilder("outbox.publish")
                    .setParent(row.traceContext().restore())
                    .setAttribute("messaging.destination.name", row.topic()).startSpan();
            try (var scope = span.makeCurrent();
                    var context = EventLogContext.open(row.key(), row.orderId(), row.eventId(), row.eventType(),
                            row.symbol(), row.scenarioId())) {
                sender.send(row.topic(), row.key(), row.payload());
                jdbc.update("update " + table + " set published_at = now() where id = ?", row.id());
                LOG.info("Committed event publication acknowledged, destination_topic={}", row.topic());
                published++;
            } catch (RuntimeException failure) {
                span.recordException(failure);
                span.setStatus(StatusCode.ERROR, "outbox publication unconfirmed");
                throw failure;
            } finally {
                span.end();
            }
        }
        return published;
    }

    public PendingSummary pendingSummary() {
        return jdbc.queryForObject("select count(*) as pending, min(created_at) as oldest from "
                + table + " where published_at is null", (rs, row) -> new PendingSummary(rs.getLong("pending"),
                        rs.getTimestamp("oldest") == null ? null : rs.getTimestamp("oldest").toInstant()));
    }

    public record PendingSummary(long count, java.time.Instant oldestCreatedAt) { }

    public void lockWriter(String name) {
        requireTransaction();
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                (ResultSetExtractor<Void>) rs -> null, table + ":" + name);
    }

    private static Row row(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        return new Row(rs.getLong("id"), rs.getString("event_id"), rs.getString("topic"),
                rs.getString("record_key"), rs.getBytes("payload"), rs.getString("order_id"),
                rs.getString("event_type"), rs.getString("symbol"), rs.getString("demo_scenario_id"),
                new OutboxTraceContext(rs.getString("traceparent"), rs.getString("tracestate")));
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox operation requires a database transaction");
        }
    }

    private static String text(SpecificRecord event, String field) {
        var schemaField = event.getSchema().getField(field);
        Object value = schemaField == null ? null : event.get(schemaField.pos());
        return value == null ? null : value.toString();
    }

    private record Row(long id, String eventId, String topic, String key, byte[] payload,
            String orderId, String eventType, String symbol, String scenarioId, OutboxTraceContext traceContext) { }
}
