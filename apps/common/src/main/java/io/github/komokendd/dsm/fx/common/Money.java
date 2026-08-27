package io.github.komokendd.dsm.fx.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

public final class Money {
    private static final Map<String, BigDecimal> USD_RATES = Map.of(
            "USD/JPY", BigDecimal.ONE,
            "EUR/USD", new BigDecimal("1.10"),
            "GBP/USD", new BigDecimal("1.27"));

    private Money() {
    }

    public static BigDecimal usdEquivalent(String currencyPair, BigDecimal quantity) {
        BigDecimal rate = USD_RATES.get(currencyPair);
        if (rate == null) {
            throw new IllegalArgumentException("Unsupported currency pair: " + currencyPair);
        }
        return quantity.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}

