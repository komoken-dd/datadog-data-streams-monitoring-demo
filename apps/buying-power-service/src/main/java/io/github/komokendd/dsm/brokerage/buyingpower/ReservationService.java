package io.github.komokendd.dsm.brokerage.buyingpower;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.Instrument;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.common.OrderType;
import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReservationService {
    private final BuyingPowerRepository repository;

    ReservationService(BuyingPowerRepository repository) {
        this.repository = repository;
    }

    /**
     * Reserves cash (BUY) or sellable shares (SELL) with a conditional update, then records the
     * reservation in the same transaction. A repeated order id returns the existing reservation;
     * a concurrent duplicate surfaces as DuplicateKeyException and rolls back.
     */
    @Transactional
    BuyingPowerReservation.Response reserve(BuyingPowerReservation.Request request) {
        var plan = ReservationPlan.of(request);
        repository.lockReservationOperation(plan.orderId());
        if (repository.cancelled(plan.orderId())) {
            return rejected(plan.orderId(), BuyingPowerReservation.RESERVATION_CANCELLED, null);
        }
        var existing = repository.findReservation(plan.orderId());
        if (existing.isPresent()) {
            var prior = existing.get();
            if (!prior.accountId().equals(plan.accountId()) || !prior.symbol().equals(plan.symbol())
                    || prior.side() != plan.side() || !prior.orderType().equals(plan.orderType().name())
                    || prior.quantity() != plan.quantity()
                    || prior.reservedAmountJpy().compareTo(plan.reservedAmountJpy()) != 0) {
                throw new IllegalArgumentException("Order ID already belongs to a different reservation");
            }
            return prior.open() ? reservedResponse(prior)
                    : rejected(plan.orderId(), BuyingPowerReservation.RESERVATION_CANCELLED, null);
        }
        if (request.reservationDeadline() != null && !java.time.Instant.now().isBefore(request.reservationDeadline())) {
            return rejected(plan.orderId(), BuyingPowerReservation.RESERVATION_EXPIRED, null);
        }
        if (repository.findAccount(plan.accountId()).isEmpty()) {
            return rejected(plan.orderId(), BuyingPowerReservation.UNKNOWN_ACCOUNT, null);
        }
        boolean reserved = plan.side() == Side.BUY
                ? repository.reserveCash(plan.accountId(), plan.reservedAmountJpy())
                : repository.reserveShares(plan.accountId(), plan.symbol(), plan.reservedQuantity());
        if (!reserved) {
            String reason = plan.side() == Side.BUY
                    ? BuyingPowerReservation.INSUFFICIENT_BUYING_POWER
                    : BuyingPowerReservation.INSUFFICIENT_SELLABLE_QUANTITY;
            return rejected(plan.orderId(), reason, currentBuyingPower(plan.accountId()));
        }
        var reservation = plan.toReservation();
        repository.insertReservation(reservation);
        return reservedResponse(reservation);
    }

    @Transactional(readOnly = true)
    java.util.Optional<BuyingPowerReservation.Response> find(String orderId) {
        return repository.findReservation(orderId).map(reservation -> new BuyingPowerReservation.Response(
                orderId, reservation.status().name(), null, reservation.reservedAmountJpy(),
                currentBuyingPower(reservation.accountId())));
    }

    @Transactional(readOnly = true)
    BuyingPowerReservation.Response existing(String orderId) {
        return repository.findReservation(orderId)
                .map(this::reservedResponse)
                .orElseThrow(() -> new IllegalStateException("Reservation disappeared: " + orderId));
    }

    /** Compensation for an order that was never accepted downstream. Idempotent. */
    @Transactional
    ReleaseResponse release(String orderId) {
        // A cancellation tombstone fences a late HTTP reservation even when no
        // reservation row existed at the moment cancellation was requested.
        repository.lockReservationOperation(orderId);
        repository.recordCancellation(orderId);
        var reservation = repository.lockReservation(orderId);
        if (reservation.isEmpty()) {
            return new ReleaseResponse(orderId, "NOT_FOUND", Jpy.of(0), 0);
        }
        var state = reservation.get();
        var delta = BuyingPowerRules.releaseRemaining(state);
        if (state.open()) {
            repository.applyToAccount(state.accountId(), delta);
            repository.applyToSellable(state.accountId(), state.symbol(), delta);
            repository.applyToReservation(orderId, delta);
        }
        return new ReleaseResponse(
                orderId, delta.reservationStatus().name(), delta.releasedAmountJpy(), delta.releasedQuantity());
    }

    private BuyingPowerReservation.Response reservedResponse(ReservationState reservation) {
        return new BuyingPowerReservation.Response(
                reservation.orderId(),
                BuyingPowerReservation.RESERVED,
                null,
                reservation.reservedAmountJpy(),
                currentBuyingPower(reservation.accountId()));
    }

    private BuyingPowerReservation.Response rejected(String orderId, String reason, BigDecimal buyingPower) {
        return new BuyingPowerReservation.Response(
                orderId, BuyingPowerReservation.REJECTED, reason, Jpy.of(0), buyingPower);
    }

    private BigDecimal currentBuyingPower(String accountId) {
        return repository.findAccount(accountId)
                .map(BuyingPowerRepository.AccountSnapshot::buyingPowerJpy)
                .orElse(null);
    }

    record ReleaseResponse(String orderId, String status, BigDecimal releasedAmountJpy, long releasedQuantity) {
    }

    /** Validated reservation request; the BUY amount follows the contract rule. */
    record ReservationPlan(
            String accountId,
            String orderId,
            Instrument instrument,
            Side side,
            OrderType orderType,
            long quantity,
            BigDecimal reservedAmountJpy,
            long reservedQuantity) {

        static ReservationPlan of(BuyingPowerReservation.Request request) {
            if (request == null || isBlank(request.accountId()) || isBlank(request.orderId())) {
                throw new IllegalArgumentException("accountId and orderId are required");
            }
            Instrument instrument = Instruments.find(request.symbol())
                    .orElseThrow(() -> new IllegalArgumentException("unknown symbol: " + request.symbol()));
            Side side = parse(() -> Side.parse(request.side()), "side must be BUY or SELL");
            OrderType orderType = parse(() -> OrderType.parse(request.orderType()), "orderType must be MARKET or LIMIT");
            if (request.quantity() <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
            if (orderType == OrderType.LIMIT && request.limitPrice() == null) {
                throw new IllegalArgumentException("a LIMIT order requires limitPrice");
            }
            BigDecimal amount = side == Side.BUY
                    ? BuyingPowerReservation.buyReservationAmount(
                                    instrument, orderType, request.limitPrice(), request.quantity())
                            .setScale(0, RoundingMode.UNNECESSARY)
                    : Jpy.of(0);
            long reservedQuantity = side == Side.SELL ? request.quantity() : 0;
            return new ReservationPlan(
                    request.accountId(),
                    request.orderId(),
                    instrument,
                    side,
                    orderType,
                    request.quantity(),
                    amount,
                    reservedQuantity);
        }

        String symbol() {
            return instrument.symbol();
        }

        ReservationState toReservation() {
            return new ReservationState(
                    orderId,
                    accountId,
                    symbol(),
                    side,
                    orderType.name(),
                    quantity,
                    reservedAmountJpy,
                    Jpy.of(0),
                    reservedQuantity,
                    0,
                    ReservationState.Status.RESERVED);
        }

        private static <T> T parse(java.util.function.Supplier<T> parser, String message) {
            try {
                return parser.get();
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(message);
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
