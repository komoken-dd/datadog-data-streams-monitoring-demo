package io.github.komokendd.dsm.brokerage.common;

import java.math.BigDecimal;

/**
 * Daily price limits (制限値幅) following the TSE table of limit widths by base price.
 * Only the bands needed for demo price levels are listed.
 */
public final class PriceLimits {
    // {base price strictly below, limit width}
    private static final long[][] TABLE = {
            {100, 30},
            {200, 50},
            {500, 80},
            {700, 100},
            {1_000, 150},
            {1_500, 300},
            {2_000, 400},
            {3_000, 500},
            {5_000, 700},
            {7_000, 1_000},
            {10_000, 1_500},
            {15_000, 3_000},
            {20_000, 4_000},
            {30_000, 5_000},
            {50_000, 7_000},
            {70_000, 10_000},
            {100_000, 15_000},
            {150_000, 30_000},
            {200_000, 40_000},
            {300_000, 50_000},
            {500_000, 70_000},
    };

    private PriceLimits() {
    }

    public static BigDecimal width(BigDecimal basePrice) {
        requirePositive(basePrice);
        for (long[] band : TABLE) {
            if (basePrice.compareTo(BigDecimal.valueOf(band[0])) < 0) {
                return BigDecimal.valueOf(band[1]);
            }
        }
        throw new IllegalArgumentException("Base price outside the demo price-limit table: " + basePrice);
    }

    public static BigDecimal upperLimit(BigDecimal basePrice) {
        return basePrice.add(width(basePrice));
    }

    public static BigDecimal lowerLimit(BigDecimal basePrice) {
        return basePrice.subtract(width(basePrice)).max(BigDecimal.ONE);
    }

    private static void requirePositive(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("Base price must be positive");
        }
    }
}
