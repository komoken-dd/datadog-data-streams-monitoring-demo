package io.github.komokendd.dsm.brokerage.holdings;

import io.github.komokendd.dsm.brokerage.common.DemoAccounts;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds the demo accounts' opening holdings. Runs during bean initialization, before the
 * Kafka listener containers start, and never overwrites rows that already exist.
 */
@Component
class HoldingsSeeder implements InitializingBean {
    private static final Logger LOG = LoggerFactory.getLogger(HoldingsSeeder.class);
    private final JdbcTemplate jdbc;

    HoldingsSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        List<Object[]> rows = seedRows();
        int inserted = 0;
        for (int count : jdbc.batchUpdate("""
                insert into holdings_service.holdings (account_id, symbol, quantity, average_cost, updated_at)
                values (?, ?, ?, ?, now())
                on conflict (account_id, symbol) do nothing
                """, rows)) {
            inserted += Math.max(count, 0);
        }
        LOG.info("Demo holdings seeded, inserted_rows={}, total_rows={}", inserted, rows.size());
    }

    static List<Object[]> seedRows() {
        List<Object[]> rows = new ArrayList<>();
        for (var account : DemoAccounts.all()) {
            account.initialShares().forEach((symbol, shares) -> rows.add(new Object[] {
                    account.accountId(), symbol, shares, openingAverageCost(symbol)}));
        }
        return rows;
    }

    /** The opening book cost is the instrument's base price (previous close). */
    static BigDecimal openingAverageCost(String symbol) {
        return Instruments.require(symbol).basePrice().setScale(AverageCost.SCALE, RoundingMode.HALF_UP);
    }
}
