package io.github.komokendd.dsm.brokerage.order;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import java.math.BigDecimal;

/** Synchronous 余力 check on the order path. */
interface BuyingPowerClient {
    ReservationOutcome reserve(BuyingPowerReservation.Request request);

    /** Best-effort compensation; never throws. */
    void release(String orderId);

    sealed interface ReservationOutcome {
    }

    record Reserved(BigDecimal reservedAmountJpy, BigDecimal buyingPowerJpy) implements ReservationOutcome {
    }

    record Rejected(String reason) implements ReservationOutcome {
    }

    record Unavailable(String detail) implements ReservationOutcome {
    }
}
