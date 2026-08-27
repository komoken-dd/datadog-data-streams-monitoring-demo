package io.github.komokendd.dsm.fx.deadletter;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class DeadLetterRepository {
    private static final String FIND_UNRESOLVED = """
            select event_id, order_id, currency_pair, side, notional_usd, demo_scenario_id,
                   retry_count, failure_category, failure_timestamp, received_at
            from dead_letter_service.dead_letters
            where resolved_at is null
            order by received_at
            """;

    private final JdbcTemplate jdbc;

    DeadLetterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<UnresolvedDeadLetter> findUnresolved() {
        return jdbc.query(FIND_UNRESOLVED, (rs, rowNumber) -> new UnresolvedDeadLetter(
                rs.getString("event_id"),
                rs.getString("order_id"),
                rs.getString("currency_pair"),
                rs.getString("side"),
                rs.getBigDecimal("notional_usd"),
                rs.getString("demo_scenario_id"),
                rs.getInt("retry_count"),
                rs.getString("failure_category"),
                rs.getTimestamp("failure_timestamp").toInstant(),
                rs.getTimestamp("received_at").toInstant()));
    }
}
