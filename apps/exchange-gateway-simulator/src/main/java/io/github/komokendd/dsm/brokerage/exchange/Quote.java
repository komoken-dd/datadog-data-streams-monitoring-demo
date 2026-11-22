package io.github.komokendd.dsm.brokerage.exchange;

import java.math.BigDecimal;

/** Public quote as served by {@code GET /internal/market/quotes}. */
public record Quote(
        String symbol,
        String name,
        BigDecimal basePrice,
        BigDecimal lastPrice,
        BigDecimal upperLimit,
        BigDecimal lowerLimit,
        boolean limitDown,
        BigDecimal changePct,
        String session) {
}
