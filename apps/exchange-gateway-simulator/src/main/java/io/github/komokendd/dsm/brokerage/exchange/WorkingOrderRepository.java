package io.github.komokendd.dsm.brokerage.exchange;

import io.github.komokendd.dsm.brokerage.common.OrderType;
import io.github.komokendd.dsm.brokerage.common.Side;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
class WorkingOrderRepository {
    private static final String COLUMNS = """
            order_id, event_id, account_id, symbol, side, order_type, quantity, limit_price,
            reserved_amount_jpy, demo_scenario_id, cumulative_quantity, next_execution_sequence,
            status, accepted_at, first_executed_at""";

    private final JdbcTemplate jdbc;

    WorkingOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: a redelivered accepted-order event does not reset the order's progress. */
    boolean insert(WorkingOrder order) {
        if (jdbc.update("insert into exchange_gateway.accepted_order_ids (order_id, accepted_at)"
                + " values (?, ?) on conflict do nothing", order.orderId(), Timestamp.from(order.acceptedAt())) == 0) {
            return false;
        }
        return jdbc.update(
                        "insert into exchange_gateway.working_orders (" + COLUMNS + ") "
                                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (order_id) do nothing",
                        order.orderId(),
                        order.eventId(),
                        order.accountId(),
                        order.symbol(),
                        order.side().name(),
                        order.orderType().name(),
                        order.quantity(),
                        order.limitPrice(),
                        order.reservedAmountJpy(),
                        order.demoScenarioId(),
                        order.cumulativeQuantity(),
                        order.nextExecutionSequence(),
                        order.status().name(),
                        Timestamp.from(order.acceptedAt()),
                        order.firstExecutedAt() == null ? null : Timestamp.from(order.firstExecutedAt()))
                == 1;
    }

    List<WorkingOrder> loadWorking(int limit) {
        return jdbc.query(
                "select " + COLUMNS + " from exchange_gateway.working_orders where status = 'WORKING' "
                        + "order by accepted_at, order_id limit ? for update",
                WorkingOrderRepository::map,
                limit);
    }

    void apply(List<TickResult.OrderUpdate> updates) {
        if (updates.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "update exchange_gateway.working_orders set cumulative_quantity = ?, next_execution_sequence = ?, "
                        + "status = ?, first_executed_at = ?, updated_at = now() where order_id = ? and status = 'WORKING'",
                updates,
                500,
                (statement, update) -> {
                    statement.setLong(1, update.cumulativeQuantity());
                    statement.setInt(2, update.nextExecutionSequence());
                    statement.setString(3, update.status().name());
                    statement.setTimestamp(4, update.firstExecutedAt() == null ? null : Timestamp.from(update.firstExecutedAt()));
                    statement.setString(5, update.orderId());
                });
    }

    Map<String, Long> countWorkingBySymbol() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query(
                "select symbol, count(*) from exchange_gateway.working_orders where status = 'WORKING' group by symbol",
                (RowCallbackHandler) rs -> counts.put(rs.getString(1), rs.getLong(2)));
        return counts;
    }

    int purgeCompletedBefore(Instant cutoff) {
        return jdbc.update(
                "delete from exchange_gateway.working_orders where status <> 'WORKING' and updated_at < ?",
                Timestamp.from(cutoff));
    }

    private static WorkingOrder map(ResultSet rs, int row) throws SQLException {
        Timestamp firstExecutedAt = rs.getTimestamp("first_executed_at");
        return new WorkingOrder(
                rs.getString("order_id"),
                rs.getString("event_id"),
                rs.getString("account_id"),
                rs.getString("symbol"),
                Side.parse(rs.getString("side")),
                OrderType.parse(rs.getString("order_type")),
                rs.getLong("quantity"),
                rs.getBigDecimal("limit_price"),
                rs.getBigDecimal("reserved_amount_jpy"),
                rs.getString("demo_scenario_id"),
                rs.getLong("cumulative_quantity"),
                rs.getInt("next_execution_sequence"),
                WorkingOrderStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("accepted_at").toInstant(),
                firstExecutedAt == null ? null : firstExecutedAt.toInstant());
    }
}
