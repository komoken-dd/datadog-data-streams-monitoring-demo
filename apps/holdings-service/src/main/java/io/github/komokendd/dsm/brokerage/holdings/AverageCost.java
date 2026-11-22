package io.github.komokendd.dsm.brokerage.holdings;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Moving-average acquisition cost (移動平均法), kept to 0.1 yen like broker statements. */
final class AverageCost {
    static final int SCALE = 1;

    private AverageCost() {
    }

    static BigDecimal afterBuy(long heldQuantity, BigDecimal averageCost, long boughtQuantity, BigDecimal price) {
        if (boughtQuantity <= 0) {
            throw new IllegalArgumentException("boughtQuantity must be positive");
        }
        if (heldQuantity <= 0 || averageCost == null) {
            return price.setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal heldCost = averageCost.multiply(BigDecimal.valueOf(heldQuantity));
        BigDecimal boughtCost = price.multiply(BigDecimal.valueOf(boughtQuantity));
        return heldCost.add(boughtCost)
                .divide(BigDecimal.valueOf(heldQuantity + boughtQuantity), SCALE, RoundingMode.HALF_UP);
    }
}
