package io.github.komokendd.dsm.brokerage.order;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.Instrument;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.order.BuyingPowerClient.Rejected;
import io.github.komokendd.dsm.brokerage.order.BuyingPowerClient.Reserved;
import io.github.komokendd.dsm.brokerage.order.BuyingPowerClient.Unavailable;
import io.github.komokendd.dsm.brokerage.order.OrderValidator.InvalidOrderException;
import io.github.komokendd.dsm.brokerage.order.OrderValidator.ValidatedOrder;
import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Order intake: validate, reserve buying power synchronously, then publish.
 * The 余力 check sits on the synchronous path, as in a real OMS.
 */
final class OrderIntakeService {
    private static final Logger LOG = LoggerFactory.getLogger(OrderIntakeService.class);
    static final String EVENT_TYPE = "ORDER_ACCEPTED";
    static final String INVALID_ORDER = "INVALID_ORDER";
    static final String BUYING_POWER_UNAVAILABLE = "BUYING_POWER_UNAVAILABLE";
    static final String ORDER_PUBLISH_FAILED = "ORDER_PUBLISH_FAILED";
    static final String UNKNOWN_TAG = "UNKNOWN";

    private final OrderValidator validator = new OrderValidator();
    private final BuyingPowerClient buyingPower;
    private final OrderEventPublisher publisher;
    private final MeterRegistry metrics;
    private final Clock clock;

    OrderIntakeService(BuyingPowerClient buyingPower, OrderEventPublisher publisher, MeterRegistry metrics, Clock clock) {
        this.buyingPower = buyingPower;
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    OrderResult submit(CreateOrderRequest request) {
        ValidatedOrder order;
        try {
            order = validator.validate(request);
        } catch (InvalidOrderException exception) {
            return invalid(request, exception.getMessage());
        }

        String orderId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        try (var ignored = EventLogContext.open(
                        order.accountId(), orderId, eventId, EVENT_TYPE, order.symbol(), order.demoScenarioId())
                .attribute("side", order.side().name())
                .attribute("order_type", order.orderType().name())) {
            var outcome = buyingPower.reserve(new BuyingPowerReservation.Request(
                    order.accountId(),
                    orderId,
                    order.symbol(),
                    order.side().name(),
                    order.orderType().name(),
                    order.quantity(),
                    order.limitPrice()));
            return switch (outcome) {
                case Reserved reserved -> publish(order, orderId, eventId, reserved);
                case Rejected rejected -> reject(order, 422, rejected.reason(), rejectMessage(rejected.reason()));
                case Unavailable unavailable -> {
                    LOG.warn("Buying-power check unavailable: {}", unavailable.detail());
                    yield reject(order, 503, BUYING_POWER_UNAVAILABLE, "buying power could not be checked; retry later");
                }
            };
        }
    }

    private OrderResult publish(ValidatedOrder order, String orderId, String eventId, Reserved reserved) {
        Instant acceptedAt = clock.instant();
        BigDecimal reservedAmount = reserved.reservedAmountJpy().setScale(0, RoundingMode.UNNECESSARY);
        var event = OrderAcceptedEvent.newBuilder()
                .setEventId(eventId)
                .setEventType(EVENT_TYPE)
                .setEventVersion(1)
                .setOccurredAt(acceptedAt)
                .setAccountId(order.accountId())
                .setOrderId(orderId)
                .setSymbol(order.symbol())
                .setSide(order.side().name())
                .setDemoScenarioId(order.demoScenarioId())
                .setOrderType(order.orderType().name())
                .setQuantity(order.quantity())
                .setLimitPrice(order.limitPrice())
                .setReservedAmountJpy(reservedAmount)
                .setAcceptedAt(acceptedAt)
                .build();
        try {
            publisher.publish(event);
        } catch (RuntimeException exception) {
            LOG.error("Accepted order could not be published; releasing the reservation", exception);
            buyingPower.release(orderId);
            return reject(order, 503, ORDER_PUBLISH_FAILED, "order could not be accepted; retry later");
        }
        Counter.builder(MetricNames.ORDERS_ACCEPTED)
                .tag("symbol", order.symbol())
                .tag("side", order.side().name())
                .tag("order_type", order.orderType().name())
                .register(metrics)
                .increment();
        LOG.info("Order accepted, quantity={}, reserved_amount_jpy={}", order.quantity(), reservedAmount.toPlainString());
        return new OrderResult.Accepted(new OrderResult.AcceptedBody(
                orderId, eventId, "ACCEPTED", reservedAmount, acceptedAt));
    }

    private OrderResult reject(ValidatedOrder order, int status, String reason, String message) {
        countRejected(order.symbol(), order.side().name(), reason);
        // Called inside the order's log context; relabel the line so rejected orders are searchable.
        String acceptedType = MDC.get("event_type");
        MDC.put("event_type", "ORDER_REJECTED");
        MDC.put("reason", reason);
        try {
            LOG.info("Order rejected, reason={}", reason);
        } finally {
            MDC.remove("reason");
            if (acceptedType == null) {
                MDC.remove("event_type");
            } else {
                MDC.put("event_type", acceptedType);
            }
        }
        return new OrderResult.Failed(status, reason, message);
    }

    OrderResult invalid(CreateOrderRequest request, String message) {
        String symbol = request == null ? null : request.symbol();
        String side = request == null ? null : request.side();
        String symbolTag = Instruments.find(symbol).map(Instrument::symbol).orElse(UNKNOWN_TAG);
        String sideTag = sideTag(side);
        countRejected(symbolTag, sideTag, INVALID_ORDER);
        try (var ignored = EventLogContext.open(
                        request == null ? null : request.accountId(),
                        null,
                        null,
                        "ORDER_REJECTED",
                        symbolTag,
                        request == null ? null : request.demoScenarioId())
                .attribute("reason", INVALID_ORDER)) {
            LOG.info("Invalid order rejected: {}", message);
        }
        return new OrderResult.Failed(400, INVALID_ORDER, message);
    }

    private void countRejected(String symbol, String side, String reason) {
        Counter.builder(MetricNames.ORDERS_REJECTED)
                .tag("symbol", symbol)
                .tag("side", side)
                .tag("reason", reason)
                .register(metrics)
                .increment();
    }

    private static String sideTag(String side) {
        try {
            return Side.parse(side).name();
        } catch (RuntimeException exception) {
            return UNKNOWN_TAG;
        }
    }

    private static String rejectMessage(String reason) {
        return switch (reason) {
            case BuyingPowerReservation.INSUFFICIENT_BUYING_POWER -> "insufficient buying power for this order";
            case BuyingPowerReservation.INSUFFICIENT_SELLABLE_QUANTITY -> "insufficient sellable quantity for this order";
            case BuyingPowerReservation.UNKNOWN_ACCOUNT -> "unknown account";
            default -> "order rejected by the buying-power check";
        };
    }
}
