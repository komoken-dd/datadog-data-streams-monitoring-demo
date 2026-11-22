package io.github.komokendd.dsm.brokerage.holdings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AverageCostTest {
    @Test
    void weightsExistingAndBoughtShares() {
        // 3,000 @ 2,800.0 + 1,000 @ 3,000.0 = 11,400,000 / 4,000 = 2,850.0
        assertThat(AverageCost.afterBuy(3_000, new BigDecimal("2800.0"), 1_000, new BigDecimal("3000.0")))
                .isEqualByComparingTo("2850.0");
    }

    @Test
    void roundsToTenthOfYenHalfUp() {
        // (100 * 980.0 + 200 * 981.0) / 300 = 980.666... -> 980.7
        assertThat(AverageCost.afterBuy(100, new BigDecimal("980.0"), 200, new BigDecimal("981.0")))
                .isEqualTo(new BigDecimal("980.7"));
        // (100 * 1000.0 + 100 * 1000.1) / 200 = 1000.05 -> 1000.1
        assertThat(AverageCost.afterBuy(100, new BigDecimal("1000.0"), 100, new BigDecimal("1000.1")))
                .isEqualTo(new BigDecimal("1000.1"));
    }

    @Test
    void firstPurchaseUsesExecutionPrice() {
        assertThat(AverageCost.afterBuy(0, new BigDecimal("2800.0"), 100, new BigDecimal("12500")))
                .isEqualTo(new BigDecimal("12500.0"));
        assertThat(AverageCost.afterBuy(0, null, 100, new BigDecimal("1650.0")))
                .isEqualTo(new BigDecimal("1650.0"));
    }

    @Test
    void rejectsNonPositivePurchase() {
        assertThatThrownBy(() -> AverageCost.afterBuy(100, BigDecimal.TEN, 0, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
