package io.github.komokendd.dsm.brokerage.buyingpower;

import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;

/**
 * One order's hold on buying power (BUY: cash) or on sellable shares (SELL).
 */
record ReservationState(
        String orderId,
        String accountId,
        String symbol,
        Side side,
        String orderType,
        long quantity,
        BigDecimal reservedAmountJpy,
        BigDecimal releasedAmountJpy,
        long reservedQuantity,
        long releasedQuantity,
        Status status) {

    enum Status {
        RESERVED,
        RELEASED,
        CONSUMED
    }

    BigDecimal remainingAmountJpy() {
        return reservedAmountJpy.subtract(releasedAmountJpy);
    }

    long remainingQuantity() {
        return reservedQuantity - releasedQuantity;
    }

    boolean open() {
        return status == Status.RESERVED;
    }
}
