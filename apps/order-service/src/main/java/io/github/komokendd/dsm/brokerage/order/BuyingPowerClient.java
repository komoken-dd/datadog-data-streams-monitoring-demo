package io.github.komokendd.dsm.brokerage.order;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import java.math.BigDecimal;

/** Reservation creation, authoritative lookup, and fenced cancellation before order acceptance. */
interface BuyingPowerClient {
    ReservationOutcome reserve(BuyingPowerReservation.Request request);
    ReservationOutcome lookup(String orderId);
    boolean cancel(String orderId);

    sealed interface ReservationOutcome { }
    record Reserved(BigDecimal reservedAmountJpy, BigDecimal buyingPowerJpy) implements ReservationOutcome { }
    record Rejected(String reason) implements ReservationOutcome { }
    record Unavailable(String detail) implements ReservationOutcome { }
    record Missing() implements ReservationOutcome { }
}
