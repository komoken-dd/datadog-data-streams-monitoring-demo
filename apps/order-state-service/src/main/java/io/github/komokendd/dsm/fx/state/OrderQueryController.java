package io.github.komokendd.dsm.fx.state;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/internal/orders")
final class OrderQueryController {
    private final JdbcTemplate jdbc;

    OrderQueryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{orderId}")
    OrderView get(@PathVariable String orderId) {
        return jdbc.query("""
                select order_id, status, currency_pair, side, notional_usd, submitted_at, updated_at, demo_scenario_id
                from order_state_service.orders where order_id = ?
                """, (rs, row) -> new OrderView(
                rs.getString("order_id"), rs.getString("status"), rs.getString("currency_pair"),
                rs.getString("side"), rs.getBigDecimal("notional_usd"),
                rs.getTimestamp("submitted_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("demo_scenario_id")), orderId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    record OrderView(
            String orderId,
            String status,
            String currencyPair,
            String side,
            BigDecimal notionalUsd,
            Instant submittedAt,
            Instant updatedAt,
            String demoScenarioId) {
    }
}
