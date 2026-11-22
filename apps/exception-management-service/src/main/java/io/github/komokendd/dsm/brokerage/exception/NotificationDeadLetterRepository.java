package io.github.komokendd.dsm.brokerage.exception;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class NotificationDeadLetterRepository {
    private static final String TABLE = "exception_management_service.notification_dead_letters";
    private static final String COLUMNS = """
            event_id, notification_id, account_id, order_id, symbol, side, notification_type,
            quantity, price, source_event_id, source_occurred_at, demo_scenario_id, retry_count,
            failure_category, failure_timestamp, originating_topic, received_at""";

    record UnresolvedSummary(long count, Instant oldestReceivedAt) {
    }

    private final JdbcTemplate jdbc;

    NotificationDeadLetterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent on event_id: Kafka redelivery of the same dead letter is a no-op. */
    boolean insert(NotificationDeadLetter row) {
        return jdbc.update(
                        "insert into " + TABLE + " (" + COLUMNS + ", resolved_at, redrive_event_id)"
                                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, null)"
                                + " on conflict (event_id) do nothing",
                        row.eventId(), row.notificationId(), row.accountId(), row.orderId(), row.symbol(),
                        row.side(), row.notificationType(), row.quantity(), row.price(), row.sourceEventId(),
                        Timestamp.from(row.sourceOccurredAt()), row.demoScenarioId(), row.retryCount(),
                        row.failureCategory(), Timestamp.from(row.failureTimestamp()), row.originatingTopic(),
                        Timestamp.from(row.receivedAt()))
                == 1;
    }

    List<NotificationDeadLetter> findUnresolved() {
        return jdbc.query("select " + COLUMNS + " from " + TABLE
                + " where resolved_at is null order by received_at, event_id", NotificationDeadLetterRepository::map);
    }

    /** Locks unresolved rows so concurrent redrive requests cannot publish the same dead letter twice. */
    List<NotificationDeadLetter> lockUnresolved() {
        return jdbc.query("select " + COLUMNS + " from " + TABLE
                        + " where resolved_at is null order by received_at, event_id for update",
                NotificationDeadLetterRepository::map);
    }

    void markResolved(String eventId, String redriveEventId) {
        jdbc.update("update " + TABLE + " set resolved_at = now(), redrive_event_id = ?"
                + " where event_id = ? and resolved_at is null", redriveEventId, eventId);
    }

    UnresolvedSummary unresolvedSummary() {
        return jdbc.queryForObject(
                "select count(*) as unresolved, min(received_at) as oldest from " + TABLE + " where resolved_at is null",
                (rs, rowNumber) -> {
                    Timestamp oldest = rs.getTimestamp("oldest");
                    return new UnresolvedSummary(rs.getLong("unresolved"), oldest == null ? null : oldest.toInstant());
                });
    }

    private static NotificationDeadLetter map(ResultSet rs, int rowNumber) throws SQLException {
        return new NotificationDeadLetter(
                rs.getString("event_id"),
                rs.getString("notification_id"),
                rs.getString("account_id"),
                rs.getString("order_id"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getString("notification_type"),
                rs.getLong("quantity"),
                rs.getBigDecimal("price"),
                rs.getString("source_event_id"),
                rs.getTimestamp("source_occurred_at").toInstant(),
                rs.getString("demo_scenario_id"),
                rs.getInt("retry_count"),
                rs.getString("failure_category"),
                rs.getTimestamp("failure_timestamp").toInstant(),
                rs.getString("originating_topic"),
                rs.getTimestamp("received_at").toInstant());
    }
}
