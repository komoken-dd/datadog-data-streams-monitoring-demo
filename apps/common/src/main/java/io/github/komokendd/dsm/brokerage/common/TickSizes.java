package io.github.komokendd.dsm.brokerage.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Standard TSE tick sizes (呼値の単位) for stocks outside TOPIX500. */
public final class TickSizes {
    // {price at or below, tick}
    private static final long[][] TABLE = {
            {3_000, 1},
            {5_000, 5},
            {30_000, 10},
            {50_000, 50},
            {300_000, 100},
            {500_000, 500},
            {3_000_000, 1_000},
    };

    private TickSizes() {
    }

    public static BigDecimal tick(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
        for (long[] band : TABLE) {
            if (price.compareTo(BigDecimal.valueOf(band[0])) <= 0) {
                return BigDecimal.valueOf(band[1]);
            }
        }
        throw new IllegalArgumentException("Price outside the demo tick table: " + price);
    }

    public static boolean onTick(BigDecimal price) {
        return price.remainder(tick(price)).signum() == 0;
    }

    /** Rounds down to a valid tick. */
    public static BigDecimal floorToTick(BigDecimal price) {
        BigDecimal tick = tick(price);
        return price.divide(tick, 0, RoundingMode.FLOOR).multiply(tick);
    }

    /** Moves an on-tick price by a number of ticks, re-evaluating the tick size at band boundaries. */
    public static BigDecimal step(BigDecimal price, int ticks) {
        BigDecimal current = price;
        for (int i = 0; i < Math.abs(ticks); i++) {
            if (ticks > 0) {
                BigDecimal candidate = current.add(tick(current));
                current = onTick(candidate) ? candidate : current.add(tick(candidate));
            } else {
                // Band boundaries are multiples of the larger tick, so subtracting the current tick stays valid.
                current = current.subtract(tick(current)).max(BigDecimal.ONE);
            }
        }
        return current;
    }
}
