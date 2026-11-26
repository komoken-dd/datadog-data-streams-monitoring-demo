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
     * A fill. BUY: release the cumulatively settled proportion of the hold (rounded down;
     * the terminal lifecycle event releases any remainder), debit the execution amount, and add the bought
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
        if (executedQuantity <= 0 || leavesQuantity < 0) {
            throw new IllegalArgumentException("Invalid execution quantities");
        }
        BigDecimal amount = executionAmountJpy.setScale(0, RoundingMode.UNNECESSARY);
        if (reservation == null) {
            return side == Side.BUY
                    ? new LedgerDelta(amount.negate(), ZERO, executedQuantity, 0, ZERO, 0, null)
                    : new LedgerDelta(amount, ZERO, -executedQuantity, 0, ZERO, 0, null);
        }
        if (reservation.side() != side || reservation.reflectedQuantity() + executedQuantity > reservation.quantity()) {
            throw new IllegalArgumentException("Execution exceeds or mismatches the reservation");
        }
        // An expiry can overtake fills on another topic. Only the expired remainder
        // and fills actually reflected here release their respective part of the hold.
        long reflected = reservation.reflectedQuantity() + executedQuantity;
        long expired = reservation.expiredQuantity() == null ? 0 : reservation.expiredQuantity();
        return lifecycleDelta(reservation, reflected, expired, reservation.expiredQuantity(),
                executedQuantity, side == Side.BUY ? amount.negate() : amount);
    }

    static LedgerDelta expire(ReservationState reservation, long expiredQuantity) {
        if (reservation == null) {
            throw new IllegalStateException("Cannot expire an order without its reservation");
        }
        if (expiredQuantity < 0 || expiredQuantity > reservation.quantity()
                || (reservation.expiredQuantity() != null && reservation.expiredQuantity() != expiredQuantity)) {
            throw new IllegalArgumentException("Invalid or conflicting expired quantity");
        }
        return lifecycleDelta(reservation, reservation.reflectedQuantity(), expiredQuantity,
                expiredQuantity, 0, ZERO);
    }

    private static LedgerDelta lifecycleDelta(ReservationState reservation, long reflected, long expired,
            Long expiryMarker, long executionQuantity, BigDecimal cashDelta) {
        long settled = Math.addExact(reflected, expired);
        if (settled > reservation.quantity()) {
            throw new IllegalArgumentException("Reflected fills and expiry exceed order quantity");
        }
        boolean complete = settled == reservation.quantity();
        var status = complete
                ? expired > 0 ? ReservationState.Status.RELEASED : ReservationState.Status.CONSUMED
                : ReservationState.Status.RESERVED;
        if (reservation.side() == Side.BUY) {
            BigDecimal target = complete ? reservation.reservedAmountJpy()
                    : reservation.reservedAmountJpy().multiply(BigDecimal.valueOf(settled))
                            .divide(BigDecimal.valueOf(reservation.quantity()), 0, RoundingMode.DOWN);
            BigDecimal release = target.subtract(reservation.releasedAmountJpy());
            if (release.signum() < 0) throw new IllegalStateException("Reservation release cannot regress");
            return new LedgerDelta(cashDelta, release.negate(), executionQuantity, 0, release, 0,
                    status, executionQuantity, expiryMarker);
        }
        long release = settled - reservation.releasedQuantity();
        if (release < 0) throw new IllegalStateException("Reservation release cannot regress");
        return new LedgerDelta(cashDelta, ZERO, -executionQuantity, -release, ZERO, release,
                status, executionQuantity, expiryMarker);
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

    static BigDecimal buyingPower(BigDecimal cashJpy, BigDecimal reservedJpy) {
        return cashJpy.subtract(reservedJpy).setScale(0, RoundingMode.UNNECESSARY);
    }
}
