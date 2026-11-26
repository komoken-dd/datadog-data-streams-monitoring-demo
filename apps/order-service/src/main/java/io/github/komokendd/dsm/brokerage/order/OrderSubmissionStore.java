package io.github.komokendd.dsm.brokerage.order;

import io.github.komokendd.dsm.brokerage.common.AvroSpecificDeserializer;
import io.github.komokendd.dsm.brokerage.common.AvroSpecificSerializer;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OrderSubmissionStore {
    private final JdbcTemplate jdbc;

    OrderSubmissionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Submission createOrLoad(String requestKey, String requestHash, OrderAcceptedEvent event, Instant deadline) {
        byte[] bytes = new AvroSpecificSerializer().serialize(Topics.ORDERS_ACCEPTED, event);
        try {
            jdbc.update("""
                insert into order_service.submissions (order_id, account_id, request_key, request_hash,
                    event_id, event_payload, reservation_deadline, state, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 'RESERVING', now(), now()) on conflict do nothing
                """, event.getOrderId(), event.getAccountId(), requestKey, requestHash,
                event.getEventId(), bytes, Timestamp.from(deadline));
        } catch (org.springframework.dao.DataAccessException failure) {
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof java.sql.SQLException sql && "P0001".equals(sql.getSQLState())
                        && sql.getMessage().contains("DEMO_EPOCH_LIMIT_REACHED")) {
                    throw new EpochLimitReachedException();
                }
                cause = cause.getCause();
            }
            throw failure;
        }
        // The deterministic order/event IDs and account/request key each have a
        // unique index. Concurrent retries may encounter any of these indexes;
        // a targeted conflict clause can still raise a primary-key violation.
        // Suppress insertion conflicts only, then require the canonical request
        // to exist with exactly the expected identity and terms.
        Submission row = jdbc.query("select * from order_service.submissions"
                        + " where account_id = ? and request_key = ?", OrderSubmissionStore::row,
                event.getAccountId(), requestKey).stream().findFirst().orElseThrow(ConflictingRequestException::new);
        if (!row.requestHash().equals(requestHash) || !row.orderId().equals(event.getOrderId())
                || !row.event().getEventId().equals(event.getEventId())) {
            throw new ConflictingRequestException();
        }
        return row;
    }

    Optional<Submission> lock(String orderId) {
        return jdbc.query("select * from order_service.submissions where order_id = ? for update",
                OrderSubmissionStore::row, orderId).stream().findFirst();
    }

    Optional<Submission> find(String orderId) {
        return jdbc.query("select * from order_service.submissions where order_id = ?",
                OrderSubmissionStore::row, orderId).stream().findFirst();
    }

    List<String> pending(int limit) {
        return jdbc.queryForList("select order_id from order_service.submissions"
                + " where state in ('RESERVING','UNKNOWN') order by created_at limit ?", String.class, limit);
    }

    RecoverySummary recoverySummary() {
        return jdbc.queryForObject("select count(*) as pending, min(created_at) as oldest from order_service.submissions"
                + " where state in ('RESERVING','UNKNOWN')", (rs, row) -> new RecoverySummary(rs.getLong("pending"),
                        rs.getTimestamp("oldest") == null ? null : rs.getTimestamp("oldest").toInstant()));
    }

    long[] epochBudget() {
        return jdbc.queryForObject("select submission_count, max_submissions from order_service.epoch_budget",
                (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)});
    }

    record RecoverySummary(long pending, Instant oldestCreatedAt) { }

    void transition(String orderId, String state, String reason) {
        jdbc.update("update order_service.submissions set state = ?, failure_reason = ?, updated_at = now()"
                + " where order_id = ?", state, reason, orderId);
    }

    private static Submission row(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        var event = new AvroSpecificDeserializer<>(OrderAcceptedEvent.class)
                .deserialize(Topics.ORDERS_ACCEPTED, rs.getBytes("event_payload"));
        return new Submission(rs.getString("order_id"), rs.getString("request_hash"),
                rs.getString("state"), rs.getString("failure_reason"),
                rs.getTimestamp("reservation_deadline").toInstant(), event);
    }

    record Submission(String orderId, String requestHash, String state, String reason, Instant deadline,
            OrderAcceptedEvent event) {
        boolean pending() { return state.equals("RESERVING") || state.equals("UNKNOWN"); }
    }

    static class ConflictingRequestException extends RuntimeException { }
    static class EpochLimitReachedException extends RuntimeException { }
}
