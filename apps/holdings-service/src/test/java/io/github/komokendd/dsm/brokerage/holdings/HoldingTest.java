package io.github.komokendd.dsm.brokerage.holdings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.common.DemoAccounts;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class HoldingTest {
    @Test
    void buyIncreasesQuantityAndRecomputesAverage() {
        var update = new Holding(3_000, new BigDecimal("2800.0"))
                .afterExecution(Side.BUY, 1_000, new BigDecimal("3000.0"));

        assertThat(update.holding().quantity()).isEqualTo(4_000);
        assertThat(update.holding().averageCost()).isEqualByComparingTo("2850.0");
        assertThat(update.clampedShares()).isZero();
    }

    @Test
    void sellDecreasesQuantityAndKeepsAverage() {
        var update = new Holding(10_000, new BigDecimal("12500.0"))
                .afterExecution(Side.SELL, 2_500, new BigDecimal("10000.0"));

        assertThat(update.holding().quantity()).isEqualTo(7_500);
        assertThat(update.holding().averageCost()).isEqualByComparingTo("12500.0");
        assertThat(update.clampedShares()).isZero();
    }

    @Test
    void sellNeverGoesBelowZero() {
        var update = new Holding(100, new BigDecimal("980.0"))
                .afterExecution(Side.SELL, 300, new BigDecimal("950.0"));

        assertThat(update.holding().quantity()).isZero();
        assertThat(update.holding().averageCost()).isEqualByComparingTo("980.0");
        assertThat(update.clampedShares()).isEqualTo(200);
    }

    @Test
    void buyIntoEmptyHoldingStartsAtExecutionPrice() {
        var update = Holding.EMPTY.afterExecution(Side.BUY, 100, new BigDecimal("4200.0"));

        assertThat(update.holding()).isEqualTo(new Holding(100, new BigDecimal("4200.0")));
    }

    @Test
    void seedsEveryAccountAndSymbolAtBasePrice() {
        var rows = HoldingsSeeder.seedRows();

        assertThat(rows).hasSize(DemoAccounts.all().size() * Instruments.symbols().size());
        assertThat(HoldingsSeeder.openingAverageCost("D105")).isEqualTo(new BigDecimal("12500.0"));
    }
}
