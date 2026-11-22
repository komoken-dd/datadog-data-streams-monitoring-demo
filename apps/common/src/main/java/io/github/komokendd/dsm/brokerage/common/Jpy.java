package io.github.komokendd.dsm.brokerage.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Yen amounts are whole numbers in this demo. */
public final class Jpy {
    private Jpy() {
    }

    public static BigDecimal amount(BigDecimal price, long quantity) {
        return price.multiply(BigDecimal.valueOf(quantity)).setScale(0, RoundingMode.HALF_UP);
    }

    public static BigDecimal of(long yen) {
        return BigDecimal.valueOf(yen).setScale(0, RoundingMode.UNNECESSARY);
    }

    /** Scales a price to the Avro price schema (scale 1). */
    public static BigDecimal price(BigDecimal price) {
        return price.setScale(1, RoundingMode.UNNECESSARY);
    }
}
