package io.github.komokendd.dsm.brokerage.common;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * HTTP contract between order-service and buying-power-service.
 *
 * <p>{@code POST /internal/buying-power/reservations} returns 200 with
 * {@code status=RESERVED}, or 422 with {@code status=REJECTED} and a reason.
 * {@code POST /internal/buying-power/reservations/{orderId}/release} releases an
 * unused reservation as compensation and is idempotent.
 */
public final class BuyingPowerReservation {
    public static final String RESERVATIONS_PATH = "/internal/buying-power/reservations";
    public static final String RESERVED = "RESERVED";
    public static final String REJECTED = "REJECTED";
    public static final String INSUFFICIENT_BUYING_POWER = "INSUFFICIENT_BUYING_POWER";
    public static final String INSUFFICIENT_SELLABLE_QUANTITY = "INSUFFICIENT_SELLABLE_QUANTITY";
    public static final String UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";
    public static final String RESERVATION_EXPIRED = "RESERVATION_EXPIRED";
    public static final String RESERVATION_CANCELLED = "RESERVATION_CANCELLED";

    private BuyingPowerReservation() {
    }

    /** Cash to reserve for a BUY order: limit price, or the daily upper limit for a market order. */
    public static BigDecimal buyReservationAmount(Instrument instrument, OrderType orderType, BigDecimal limitPrice, long quantity) {
        BigDecimal price = orderType == OrderType.MARKET ? instrument.upperLimitPrice() : limitPrice;
        return Jpy.amount(price, quantity);
    }

    public record Request(
            String accountId,
            String orderId,
            String symbol,
            String side,
            String orderType,
            long quantity,
            BigDecimal limitPrice,
            Instant reservationDeadline) {
        public Request(String accountId, String orderId, String symbol, String side, String orderType,
                long quantity, BigDecimal limitPrice) {
            this(accountId, orderId, symbol, side, orderType, quantity, limitPrice, null);
        }
    }

    public record Response(
            String orderId,
            String status,
            String reason,
            BigDecimal reservedAmountJpy,
            BigDecimal buyingPowerJpy) {
    }
}
