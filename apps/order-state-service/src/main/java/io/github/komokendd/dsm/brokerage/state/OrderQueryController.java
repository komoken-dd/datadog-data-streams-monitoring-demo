package io.github.komokendd.dsm.brokerage.state;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/orders")
class OrderQueryController {
    private final JdbcTemplate jdbc;

    OrderQueryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{orderId}")
    OrderView get(@PathVariable String orderId) {
        List<ExecutionView> executions = jdbc.query("""
                select execution_event_id, execution_id, sequence, quantity, price, executed_at, buying_power_reflected_at,
                       trade_report_issued_at, notification_delivered_at
                  from order_state_service.executions
                 where order_id = ?
                 order by sequence, executed_at
                """, (rs, row) -> new ExecutionView(
                rs.getString("execution_event_id"),
                rs.getString("execution_id"),
                rs.getInt("sequence"),
                rs.getLong("quantity"),
                rs.getBigDecimal("price"),
                OrderStateStore.instant(rs, "executed_at"),
                OrderStateStore.instant(rs, "buying_power_reflected_at"),
                OrderStateStore.instant(rs, "trade_report_issued_at"),
                OrderStateStore.instant(rs, "notification_delivered_at")), orderId);
        return jdbc.query("""
                select order_id, account_id, symbol, side, order_type, quantity, cumulative_quantity, status,
                       accepted_at, updated_at, demo_scenario_id
                  from order_state_service.orders
                 where order_id = ?
                """, (rs, row) -> new OrderView(
                rs.getString("order_id"),
                rs.getString("account_id"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getString("order_type"),
                rs.getLong("quantity"),
                rs.getLong("cumulative_quantity"),
                rs.getString("status"),
                OrderStateStore.instant(rs, "accepted_at"),
                OrderStateStore.instant(rs, "updated_at"),
                rs.getString("demo_scenario_id"),
                executions), orderId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    record OrderView(
            String orderId,
            String accountId,
            String symbol,
            String side,
            String orderType,
            long quantity,
            long cumulativeQuantity,
            String status,
            Instant acceptedAt,
            Instant updatedAt,
            String demoScenarioId,
            List<ExecutionView> executions) {
    }

    record ExecutionView(
            String executionEventId,
            String executionId,
            int sequence,
            long quantity,
            BigDecimal price,
            Instant executedAt,
            Instant buyingPowerReflectedAt,
            Instant tradeReportIssuedAt,
            Instant notificationDeliveredAt) {
    }
}
