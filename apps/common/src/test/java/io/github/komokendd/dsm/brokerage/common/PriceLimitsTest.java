package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class PriceLimitsTest {
    @ParameterizedTest
    @CsvSource({
            "99, 30", "100, 50", "980, 150", "1000, 300", "1650, 400",
            "2800, 500", "3000, 700", "4200, 700", "9999, 1500", "12500, 3000", "15000, 4000"})
    void usesTheTseLimitWidthForTheBasePriceBand(String basePrice, String width) {
        assertThat(PriceLimits.width(new BigDecimal(basePrice))).isEqualByComparingTo(width);
    }

    @Test
    void limitsAreSymmetricAroundTheBasePrice() {
        var base = new BigDecimal("12500");
        assertThat(PriceLimits.upperLimit(base)).isEqualByComparingTo("15500");
        assertThat(PriceLimits.lowerLimit(base)).isEqualByComparingTo("9500");
    }

    @Test
    void everyDemoInstrumentHasOnTickLimitPrices() {
        for (Instrument instrument : Instruments.all()) {
            assertThat(TickSizes.onTick(instrument.upperLimitPrice())).as(instrument.symbol()).isTrue();
            assertThat(TickSizes.onTick(instrument.lowerLimitPrice())).as(instrument.symbol()).isTrue();
            assertThat(TickSizes.onTick(instrument.basePrice())).as(instrument.symbol()).isTrue();
        }
    }

    @Test
    void rejectsNonPositiveBasePrices() {
        assertThatThrownBy(() -> PriceLimits.width(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
