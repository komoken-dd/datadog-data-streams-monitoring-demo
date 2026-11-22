package io.github.komokendd.dsm.brokerage.state;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/**
 * JDBC access for the projection. Callers run inside a transaction; every write first takes a
 * transaction-scoped advisory lock on the order id, so the per-topic listener threads that touch
 * the same order (accepted, executions, downstream outcomes) are serialized without relying on
 * row locks for rows that may not exist yet.
 */
@Repository
class OrderStateStore {
    private static final String ORDER_COLUMNS = """
            order_id, account_id, symbol, side, order_type, quantity, cumulative_quantity, status,
            accepted_at, demo_scenario_id""";
    private static final String PROGRESS_COLUMNS = """
            execution_event_id, buying_power_reflected_at, trade_report_issued_at,
            notification_status, notification_delivered_at""";
    private final JdbcTemplate jdbc;

    OrderStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Claims an (event, transition) pair; false means this transition was already applied. */
    boolean claim(String eventId, String transitionKey) {
        return jdbc.update("""
                insert into order_state_service.processed_transitions (event_id, transition_key, processed_at)
                values (?, ?, now()) on conflict do nothing
                """, eventId, transitionKey) == 1;
    }

    OrderTransition applyOrder(OrderSnapshot incoming) {
        lockOrder(incoming.orderId());
        OrderSnapshot current = jdbc.query(
                        "select " + ORDER_COLUMNS + " from order_state_service.orders where order_id = ? for update",
                        (rs, row) -> order(rs), incoming.orderId())
                .stream().findFirst().orElse(null);
        OrderSnapshot merged = OrderSnapshot.merge(current, incoming);
        if (current == null) {
            jdbc.update("""
                    insert into order_state_service.orders
                        (order_id, account_id, symbol, side, order_type, quantity, cumulative_quantity, status,
                         status_rank, accepted_at, updated_at, demo_scenario_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)
                    """, merged.orderId(), merged.accountId(), merged.symbol(), merged.side(), merged.orderType(),
                    merged.quantity(), merged.cumulativeQuantity(), merged.status().name(), merged.status().rank(),
                    Timestamp.from(merged.acceptedAt()), merged.demoScenarioId());
        } else if (!merged.equals(current)) {
            jdbc.update("""
                    update order_state_service.orders
                       set cumulative_quantity = ?, status = ?, status_rank = ?, demo_scenario_id = ?,
                           updated_at = now()
                     where order_id = ?
                    """, merged.cumulativeQuantity(), merged.status().name(), merged.status().rank(),
                    merged.demoScenarioId(), merged.orderId());
        }
        return new OrderTransition(current == null ? null : current.status(), merged, !merged.equals(current));
    }

    /**
     * Stores the execution once and folds in any downstream outcomes that arrived before it.
     *
     * @return number of parked marks applied, or -1 when the execution was already stored
     */
    int recordExecution(ExecutionRecord execution) {
        lockOrder(execution.orderId());
        int inserted = jdbc.update("""
                insert into order_state_service.executions
                    (execution_event_id, execution_id, order_id, account_id, symbol, side, sequence, quantity,
                     price, executed_at, notification_status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (execution_event_id) do nothing
                """, execution.executionEventId(), execution.executionId(), execution.orderId(),
                execution.accountId(), execution.symbol(), execution.side(), execution.sequence(),
                execution.quantity(), execution.price(), Timestamp.from(execution.executedAt()),
                NotificationStatus.PENDING.name());
        if (inserted == 0) {
            return -1;
        }
        List<ParkedMark> parked = jdbc.query("""
                select id, mark_type, marked_at, redriven from order_state_service.pending_execution_marks
                 where order_id = ?
                   and ((match_key = 'EXECUTION_EVENT_ID' and match_value = ?)
                     or (match_key = 'EXECUTION_ID' and match_value = ?))
                 order by id
                """, (rs, row) -> new ParkedMark(rs.getLong("id"), new ExecutionMark(
                        ExecutionMark.Type.valueOf(rs.getString("mark_type")),
                        instant(rs, "marked_at"),
                        rs.getBoolean("redriven"))),
                execution.orderId(), execution.executionEventId(), execution.executionId());
        if (parked.isEmpty()) {
            return 0;
        }
        ExecutionProgress progress = ExecutionProgress.INITIAL.applyAll(parked.stream().map(ParkedMark::mark).toList());
        updateProgress(execution.executionEventId(), progress);
        jdbc.batchUpdate("delete from order_state_service.pending_execution_marks where id = ?",
                parked.stream().map(mark -> new Object[] {mark.id()}).toList());
        return parked.size();
    }

    /** Applies the mark to the execution row, or parks it when the execution has not arrived yet. */
    MarkResult applyMark(String orderId, String matchValue, ExecutionMark mark) {
        lockOrder(orderId);
        String column = mark.matchKey() == ExecutionMark.MatchKey.EXECUTION_ID ? "execution_id" : "execution_event_id";
        Optional<StoredProgress> stored = jdbc.query(
                        "select " + PROGRESS_COLUMNS + " from order_state_service.executions where "
                                + column + " = ? for update",
                        (rs, row) -> new StoredProgress(rs.getString("execution_event_id"), new ExecutionProgress(
                                instant(rs, "buying_power_reflected_at"),
                                instant(rs, "trade_report_issued_at"),
                                NotificationStatus.valueOf(rs.getString("notification_status")),
                                instant(rs, "notification_delivered_at"))),
                        matchValue)
                .stream().findFirst();
        if (stored.isEmpty()) {
            jdbc.update("""
                    insert into order_state_service.pending_execution_marks
                        (order_id, match_key, match_value, mark_type, marked_at, redriven, received_at)
                    values (?, ?, ?, ?, ?, ?, now())
                    """, orderId, mark.matchKey().name(), matchValue, mark.type().name(),
                    mark.at() == null ? null : Timestamp.from(mark.at()), mark.redriven());
            return new MarkResult(false, null);
        }
        ExecutionProgress next = stored.get().progress().apply(mark);
        if (!next.equals(stored.get().progress())) {
            updateProgress(stored.get().executionEventId(), next);
        }
        return new MarkResult(true, next);
    }

    private void updateProgress(String executionEventId, ExecutionProgress progress) {
        jdbc.update("""
                update order_state_service.executions
                   set buying_power_reflected_at = ?, trade_report_issued_at = ?,
                       notification_status = ?, notification_delivered_at = ?
                 where execution_event_id = ?
                """, timestamp(progress.buyingPowerReflectedAt()), timestamp(progress.tradeReportIssuedAt()),
                progress.notificationStatus().name(), timestamp(progress.notificationDeliveredAt()),
                executionEventId);
    }

    private void lockOrder(String orderId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                (ResultSetExtractor<Void>) rs -> null, orderId);
    }

    private static OrderSnapshot order(ResultSet rs) throws SQLException {
        return new OrderSnapshot(
                rs.getString("order_id"),
                rs.getString("account_id"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getString("order_type"),
                rs.getLong("quantity"),
                rs.getLong("cumulative_quantity"),
                OrderStatus.valueOf(rs.getString("status")),
                instant(rs, "accepted_at"),
                rs.getString("demo_scenario_id"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    record OrderTransition(OrderStatus previous, OrderSnapshot order, boolean changed) {
    }

    record MarkResult(boolean applied, ExecutionProgress progress) {
    }

    private record ParkedMark(long id, ExecutionMark mark) {
    }

    private record StoredProgress(String executionEventId, ExecutionProgress progress) {
    }
}
