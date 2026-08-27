package io.github.komokendd.dsm.fx.generator;

import java.math.BigDecimal;
import java.util.Random;

/**
 * Reproducible weighted traffic mix for the demo order stream.
 *
 * <p>The total emission rate remains controlled by {@link GeneratorControl}; this profile only
 * varies the bounded business dimensions of each order.
 */
final class OrderTrafficProfile {
    private static final BigDecimal QUANTITY_100K = new BigDecimal("100000.00");
    private static final BigDecimal QUANTITY_250K = new BigDecimal("250000.00");
    private static final BigDecimal QUANTITY_500K = new BigDecimal("500000.00");
    private static final BigDecimal QUANTITY_1M = new BigDecimal("1000000.00");
    private static final BigDecimal QUANTITY_7_5M = new BigDecimal("7500000.00");

    private final Random random;

    OrderTrafficProfile(long seed) {
        this.random = new Random(seed);
    }

    OrderSample next() {
        String side = random.nextInt(100) < 55 ? "BUY" : "SELL";
        return new OrderSample(selectCurrencyPair(side), side, selectQuantity());
    }

    private String selectCurrencyPair(String side) {
        int draw = random.nextInt(100);
        if ("BUY".equals(side)) {
            if (draw < 50) {
                return "USD/JPY";
            }
            return draw < 80 ? "EUR/USD" : "GBP/USD";
        }
        if (draw < 35) {
            return "USD/JPY";
        }
        return draw < 80 ? "EUR/USD" : "GBP/USD";
    }

    private BigDecimal selectQuantity() {
        int draw = random.nextInt(100);
        if (draw < 35) {
            return QUANTITY_100K;
        }
        if (draw < 65) {
            return QUANTITY_250K;
        }
        if (draw < 85) {
            return QUANTITY_500K;
        }
        if (draw < 95) {
            return QUANTITY_1M;
        }
        return QUANTITY_7_5M;
    }

    record OrderSample(String currencyPair, String side, BigDecimal quantity) {}
}
