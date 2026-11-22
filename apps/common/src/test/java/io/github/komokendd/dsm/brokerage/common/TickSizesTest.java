package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TickSizesTest {
    @ParameterizedTest
    @CsvSource({"980, 1", "3000, 1", "3001, 5", "5000, 5", "5010, 10", "12500, 10", "30000, 10", "30050, 50"})
    void usesTheStandardTickTable(String price, String tick) {
        assertThat(TickSizes.tick(new BigDecimal(price))).isEqualByComparingTo(tick);
    }

    @Test
    void detectsOffTickPrices() {
        assertThat(TickSizes.onTick(new BigDecimal("4205"))).isTrue();
        assertThat(TickSizes.onTick(new BigDecimal("4203"))).isFalse();
        assertThat(TickSizes.floorToTick(new BigDecimal("4203"))).isEqualByComparingTo("4200");
    }

    @Test
    void stepsAcrossBandBoundaries() {
        assertThat(TickSizes.step(new BigDecimal("2999"), 1)).isEqualByComparingTo("3000");
        assertThat(TickSizes.step(new BigDecimal("3000"), 1)).isEqualByComparingTo("3005");
        assertThat(TickSizes.step(new BigDecimal("3005"), -1)).isEqualByComparingTo("3000");
        assertThat(TickSizes.step(new BigDecimal("3000"), -1)).isEqualByComparingTo("2999");
        assertThat(TickSizes.step(new BigDecimal("12500"), -3)).isEqualByComparingTo("12470");
    }

    @Test
    void steppedPricesAreAlwaysOnTick() {
        BigDecimal price = new BigDecimal("2950");
        for (int i = 0; i < 400; i++) {
            price = TickSizes.step(price, 1);
            assertThat(TickSizes.onTick(price)).as(price.toPlainString()).isTrue();
        }
        for (int i = 0; i < 400; i++) {
            price = TickSizes.step(price, -1);
            assertThat(TickSizes.onTick(price)).as(price.toPlainString()).isTrue();
        }
    }
}
