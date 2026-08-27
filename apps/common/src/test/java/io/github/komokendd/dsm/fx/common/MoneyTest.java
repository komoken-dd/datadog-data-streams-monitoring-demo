package io.github.komokendd.dsm.fx.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void calculatesStableSyntheticUsdEquivalent() {
        assertThat(Money.usdEquivalent("EUR/USD", new BigDecimal("1000000.00")))
                .isEqualByComparingTo("1100000.00");
    }

    @Test
    void rejectsUnknownPair() {
        assertThatThrownBy(() -> Money.usdEquivalent("AUD/USD", BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
