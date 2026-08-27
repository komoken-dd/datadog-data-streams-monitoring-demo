package io.github.komokendd.dsm.fx.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SyntheticFeeCalculatorTest {
    @Test
    void calculatesFictionalFeeFromUsdEquivalentNotional() {
        assertThat(SyntheticFeeCalculator.feeUsd("USD/JPY", new BigDecimal("1000000.00")))
                .isEqualByComparingTo("40.00");
        assertThat(SyntheticFeeCalculator.feeUsd("EUR/USD", new BigDecimal("1000000.00")))
                .isEqualByComparingTo("30.00");
        assertThat(SyntheticFeeCalculator.feeUsd("GBP/USD", new BigDecimal("1000000.00")))
                .isEqualByComparingTo("50.00");
    }

    @Test
    void roundsToUsdCents() {
        assertThat(SyntheticFeeCalculator.feeUsd("EUR/USD", new BigDecimal("12345.67")))
                .isEqualByComparingTo("0.37");
    }

    @Test
    void rejectsUnknownPairAndNegativeNotional() {
        assertThatThrownBy(() -> SyntheticFeeCalculator.feeUsd("AUD/USD", BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SyntheticFeeCalculator.feeUsd("EUR/USD", new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
