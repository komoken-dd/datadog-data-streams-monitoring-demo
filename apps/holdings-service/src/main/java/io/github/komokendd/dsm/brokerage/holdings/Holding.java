package io.github.komokendd.dsm.brokerage.holdings;

import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Shares held for one account and symbol. */
record Holding(long quantity, BigDecimal averageCost) {
    static final Holding EMPTY = new Holding(0, null);

    /**
     * Applies one fill. A BUY recomputes the moving average; a SELL keeps the average
     * cost and never takes the quantity below zero (the shortfall is reported instead).
     */
    Update afterExecution(Side side, long executedQuantity, BigDecimal executionPrice) {
        if (executedQuantity <= 0) {
            throw new IllegalArgumentException("executedQuantity must be positive");
        }
        if (side == Side.BUY) {
            BigDecimal average = AverageCost.afterBuy(quantity, averageCost, executedQuantity, executionPrice);
            return new Update(new Holding(quantity + executedQuantity, average), 0);
        }
        long remaining = quantity - executedQuantity;
        BigDecimal average = averageCost == null
                ? executionPrice.setScale(AverageCost.SCALE, RoundingMode.HALF_UP)
                : averageCost;
        return new Update(new Holding(Math.max(0, remaining), average), Math.max(0, -remaining));
    }

    record Update(Holding holding, long clampedShares) {
    }
}
