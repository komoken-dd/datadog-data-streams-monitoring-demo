package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BuyingPowerReservationTest {
    private final Instrument semiconductor = Instruments.require("D105");

    @Test
    void reservesLimitOrdersAtTheLimitPrice() {
        assertThat(BuyingPowerReservation.buyReservationAmount(semiconductor, OrderType.LIMIT, new BigDecimal("12400"), 200))
                .isEqualByComparingTo("2480000");
    }

    @Test
    void reservesMarketOrdersAtTheDailyUpperLimit() {
        assertThat(BuyingPowerReservation.buyReservationAmount(semiconductor, OrderType.MARKET, null, 100))
                .isEqualByComparingTo("1550000");
    }
}
