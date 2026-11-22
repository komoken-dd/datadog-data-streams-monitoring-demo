package io.github.komokendd.dsm.brokerage.common;

import java.math.BigDecimal;

/**
 * Fictional listed stock.
 *
 * @param symbol    fictional code; deliberately not a real TSE code
 * @param name      display name
 * @param basePrice 基準値 (previous close) that determines the daily price limit
 */
public record Instrument(String symbol, String name, BigDecimal basePrice) {
    public static final long TRADING_UNIT = 100;

    public BigDecimal upperLimitPrice() {
        return PriceLimits.upperLimit(basePrice);
    }

    public BigDecimal lowerLimitPrice() {
        return PriceLimits.lowerLimit(basePrice);
    }

    public boolean withinDailyLimit(BigDecimal price) {
        return price.compareTo(lowerLimitPrice()) >= 0 && price.compareTo(upperLimitPrice()) <= 0;
    }
}
