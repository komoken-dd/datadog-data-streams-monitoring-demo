package io.github.komokendd.dsm.brokerage.buyingpower;

import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure buying-power arithmetic. Commission is zero (major Japanese online brokers' pricing).
 */
final class BuyingPowerRules {
    private static final BigDecimal ZERO = Jpy.of(0);

    private BuyingPowerRules() {
    }

    /**
     * A fill. BUY: release {@code reserved * executed / quantity} (rounded down; the last fill releases
     * the remainder so the reservation never drifts), debit the execution amount, and add the bought
     * shares to sellable (same-day resale is allowed). SELL: remove the shares, release their hold,
     * and credit the proceeds immediately (same-day reuse of sale proceeds).
     *
     * @param reservation the order's reservation, or null when it is unknown to this service
     */
    static LedgerDelta fill(
            Side side,
            ReservationState reservation,
            long executedQuantity,
            long leavesQuantity,
            BigDecimal executionAmountJpy) {
        boolean lastFill = leavesQuantity == 0;
        ReservationState.Status status = reservation == null
                ? null
                : reservation.open() && lastFill ? ReservationState.Status.CONSUMED : reservation.status();
        BigDecimal amount = executionAmountJpy.setScale(0, RoundingMode.UNNECESSARY);
        if (side == Side.BUY) {
            BigDecimal release = buyFillRelease(reservation, executedQuantity, lastFill);
            return new LedgerDelta(amount.negate(), release.negate(), executedQuantity, 0, release, 0, status);
        }
        long releaseQuantity = sellFillRelease(reservation, executedQuantity, lastFill);
        return new LedgerDelta(amount, ZERO, -executedQuantity, -releaseQuantity, ZERO, releaseQuantity, status);
    }

    /** Expiry or compensation: release whatever is still held. */
    static LedgerDelta releaseRemaining(ReservationState reservation) {
        if (reservation == null || !reservation.open()) {
            return new LedgerDelta(ZERO, ZERO, 0, 0, ZERO, 0, reservation == null ? null : reservation.status());
        }
        BigDecimal amount = reservation.remainingAmountJpy().max(ZERO);
        long quantity = Math.max(0, reservation.remainingQuantity());
        return new LedgerDelta(ZERO, amount.negate(), 0, -quantity, amount, quantity, ReservationState.Status.RELEASED);
    }

    static BigDecimal buyFillRelease(ReservationState reservation, long executedQuantity, boolean lastFill) {
        if (reservation == null || !reservation.open() || reservation.quantity() <= 0) {
            return ZERO;
        }
        BigDecimal remaining = reservation.remainingAmountJpy().max(ZERO);
        if (lastFill) {
            return remaining;
        }
        BigDecimal proportional = reservation.reservedAmountJpy()
                .multiply(BigDecimal.valueOf(executedQuantity))
                .divide(BigDecimal.valueOf(reservation.quantity()), 0, RoundingMode.DOWN);
        return proportional.min(remaining);
    }

    static long sellFillRelease(ReservationState reservation, long executedQuantity, boolean lastFill) {
        if (reservation == null || !reservation.open()) {
            return 0;
        }
        long remaining = Math.max(0, reservation.remainingQuantity());
        return lastFill ? remaining : Math.min(executedQuantity, remaining);
    }

    static BigDecimal buyingPower(BigDecimal cashJpy, BigDecimal reservedJpy) {
        return cashJpy.subtract(reservedJpy).setScale(0, RoundingMode.UNNECESSARY);
    }
}
