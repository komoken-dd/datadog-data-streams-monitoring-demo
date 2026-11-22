package io.github.komokendd.dsm.brokerage.buyingpower;

import java.math.BigDecimal;

/**
 * Changes one event applies to an account, its sellable shares and the order's reservation.
 *
 * @param cashDeltaJpy            change to cash (BUY fill debits, SELL fill credits)
 * @param reservedDeltaJpy        change to the account's reserved cash (negative = released)
 * @param sellableQuantityDelta   change to held shares
 * @param sellableReservedDelta   change to shares reserved by SELL orders (negative = released)
 * @param releasedAmountJpy       cash released from this order's reservation
 * @param releasedQuantity        shares released from this order's reservation
 * @param reservationStatus       reservation status after the event, or null when there is no reservation
 */
record LedgerDelta(
        BigDecimal cashDeltaJpy,
        BigDecimal reservedDeltaJpy,
        long sellableQuantityDelta,
        long sellableReservedDelta,
        BigDecimal releasedAmountJpy,
        long releasedQuantity,
        ReservationState.Status reservationStatus) {
}
