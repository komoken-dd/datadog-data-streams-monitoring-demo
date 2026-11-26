package io.github.komokendd.dsm.brokerage.holdings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Operator-only receipt check: the event claim commits atomically with the holdings update. */
@RestController
class HoldingsQueryController {
    private final JdbcTemplate jdbc;

    HoldingsQueryController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/internal/holdings/events/{eventId}")
    Receipt event(@PathVariable String eventId) {
        boolean applied = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from holdings_service.processed_events where event_id = ?)",
                Boolean.class, eventId));
        return new Receipt(applied);
    }

    record Receipt(boolean applied) { }
}
