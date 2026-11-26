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
        Status status,
        long reflectedQuantity,
        Long expiredQuantity) {

    ReservationState(String orderId, String accountId, String symbol, Side side, String orderType, long quantity,
            BigDecimal reservedAmountJpy, BigDecimal releasedAmountJpy, long reservedQuantity,
            long releasedQuantity, Status status) {
        this(orderId, accountId, symbol, side, orderType, quantity, reservedAmountJpy, releasedAmountJpy,
                reservedQuantity, releasedQuantity, status, inferredReflectedQuantity(side, quantity,
                        reservedAmountJpy, releasedAmountJpy, releasedQuantity, status), null);
    }

    private static long inferredReflectedQuantity(Side side, long quantity, BigDecimal reserved,
            BigDecimal released, long releasedShares, Status status) {
        if (status == Status.CONSUMED) return quantity;
        if (side == Side.SELL) return releasedShares;
        if (reserved.signum() == 0) return 0;
        return released.multiply(BigDecimal.valueOf(quantity)).divide(reserved, 0,
                java.math.RoundingMode.CEILING).longValueExact();
    }

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
