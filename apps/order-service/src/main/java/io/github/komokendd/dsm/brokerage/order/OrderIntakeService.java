package io.github.komokendd.dsm.brokerage.order;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.Instrument;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.github.komokendd.dsm.brokerage.common.JdbcEventOutbox;
import io.github.komokendd.dsm.brokerage.common.EventIds;
import io.github.komokendd.dsm.brokerage.common.Topics;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
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

/**
 * Order intake: validate, reserve buying power synchronously, then publish.
 * The 余力 check sits on the synchronous path, as in a real OMS.
 */
final class OrderIntakeService {
    private static final Logger LOG = LoggerFactory.getLogger(OrderIntakeService.class);
    static final String EVENT_TYPE = "ORDER_ACCEPTED";
    static final String INVALID_ORDER = "INVALID_ORDER";
    static final String UNKNOWN_TAG = "UNKNOWN";

    private final OrderValidator validator = new OrderValidator();
    private final BuyingPowerClient buyingPower;
    private final OrderSubmissionStore submissions;
    private final JdbcEventOutbox outbox;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    private final Clock clock;

    OrderIntakeService(BuyingPowerClient buyingPower, MeterRegistry metrics, Clock clock,
            OrderSubmissionStore submissions, JdbcEventOutbox outbox, TransactionTemplate transactions) {
        this.buyingPower = buyingPower;
        this.submissions = submissions;
        this.outbox = outbox;
        this.transactions = transactions;
        this.metrics = metrics;
        this.clock = clock;
    }

    OrderResult submit(CreateOrderRequest request) {
        return submit(request, null);
    }

    OrderResult submit(CreateOrderRequest request, String idempotencyKey) {
        ValidatedOrder order;
        try {
            order = validator.validate(request);
            if (idempotencyKey != null && !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new InvalidOrderException("Idempotency-Key must be 1-128 safe identifier characters");
            }
        } catch (InvalidOrderException exception) {
            return invalid(request, exception.getMessage());
        }
        String key = idempotencyKey == null ? UUID.randomUUID().toString() : idempotencyKey;
        String orderId = EventIds.derived(order.accountId() + ":" + key, "ORDER_ID");
        String eventId = EventIds.derived(orderId, EVENT_TYPE);
        Instant acceptedAt = clock.instant();
        BigDecimal amount = order.side() == Side.BUY
                ? BuyingPowerReservation.buyReservationAmount(order.instrument(), order.orderType(),
                        order.limitPrice(), order.quantity()) : BigDecimal.ZERO;
        var event = OrderAcceptedEvent.newBuilder()
                .setEventId(eventId).setEventType(EVENT_TYPE).setEventVersion(1).setOccurredAt(acceptedAt)
                .setAccountId(order.accountId()).setOrderId(orderId).setSymbol(order.symbol())
                .setSide(order.side().name()).setDemoScenarioId(order.demoScenarioId())
                .setOrderType(order.orderType().name()).setQuantity(order.quantity()).setLimitPrice(order.limitPrice())
                .setReservedAmountJpy(amount.setScale(0, RoundingMode.UNNECESSARY)).setAcceptedAt(acceptedAt).build();
        try {
            transactions.execute(status -> submissions.createOrLoad(key, fingerprint(order), event,
                    acceptedAt.plusSeconds(30)));
        } catch (OrderSubmissionStore.EpochLimitReachedException limit) {
            Counter.builder("demo.brokerage.demo_epoch.limit_rejections").register(metrics).increment();
            return new OrderResult.Failed(429, "DEMO_EPOCH_LIMIT_REACHED",
                    "stop generation and prepare a new demo epoch before submitting more orders");
        } catch (OrderSubmissionStore.ConflictingRequestException conflict) {
            return new OrderResult.Failed(409, "IDEMPOTENCY_KEY_CONFLICT",
                    "this key already identifies a different order");
        }
        return advance(orderId, true);
    }

    Optional<OrderResult> status(String orderId) {
        return submissions.find(orderId).map(this::result);
    }

    /** Resume only the original durable intent; never create a new order to recover an uncertain one. */
    OrderResult advance(String orderId, boolean mayReserve) {
        return transactions.execute(tx -> {
            var row = submissions.lock(orderId).orElseThrow();
            if (!row.pending()) return result(row);
            var event = row.event();
            try (var ignored = EventLogContext.open(event.getAccountId(), orderId, event.getEventId(),
                    "ORDER_SUBMISSION", event.getSymbol(), event.getDemoScenarioId())) {
                if (!clock.instant().isBefore(row.deadline())) {
                    if (buyingPower.cancel(orderId)) {
                        submissions.transition(orderId, "CANCELLED", "RESERVATION_DEADLINE_EXCEEDED");
                        return new OrderResult.Failed(409, "ORDER_CANCELLED",
                                "reservation did not complete before the acceptance deadline");
                    }
                    submissions.transition(orderId, "UNKNOWN", "CANCELLATION_PENDING");
                    return pending(event);
                }
                var outcome = mayReserve && row.state().equals("RESERVING")
                        ? buyingPower.reserve(new BuyingPowerReservation.Request(event.getAccountId(), orderId,
                                event.getSymbol(), event.getSide(), event.getOrderType(), event.getQuantity(),
                                event.getLimitPrice(), row.deadline()))
                        : buyingPower.lookup(orderId);
                if (outcome instanceof Reserved reserved) {
                    if (reserved.reservedAmountJpy().compareTo(event.getReservedAmountJpy()) != 0) {
                        throw new IllegalStateException("Reservation amount does not match durable order");
                    }
                    // The hold exists. Acceptance and the immutable event become durable in
                    // one local transaction. A broker timeout can never release this hold.
                    outbox.lockWriter("acceptance:" + event.getAccountId());
                    outbox.append(event.getEventId(), Topics.ORDERS_ACCEPTED, event.getAccountId(), event);
                    submissions.transition(orderId, "ACCEPTED", null);
                    afterCommit(() -> {
                        Counter.builder(MetricNames.ORDERS_ACCEPTED).tag("symbol", event.getSymbol())
                                .tag("side", event.getSide()).tag("order_type", event.getOrderType())
                                .register(metrics).increment();
                        try (var acceptedLog = EventLogContext.open(event.getAccountId(), orderId, event.getEventId(),
                                EVENT_TYPE, event.getSymbol(), event.getDemoScenarioId())) {
                            LOG.info("Order accepted durably, quantity={}, reserved_amount_jpy={}",
                                    event.getQuantity(), event.getReservedAmountJpy());
                        }
                    });
                    return accepted(event);
                }
                if (outcome instanceof Rejected rejected) {
                    submissions.transition(orderId, "REJECTED", rejected.reason());
                    countRejected(event.getSymbol(), event.getSide(), rejected.reason());
                    try (var rejectedLog = EventLogContext.open(event.getAccountId(), orderId, event.getEventId(),
                            "ORDER_REJECTED", event.getSymbol(), event.getDemoScenarioId())
                            .attribute("reason", rejected.reason())) {
                        LOG.info("Order reservation rejected, reason={}", rejected.reason());
                    }
                    return new OrderResult.Failed(422, rejected.reason(), rejectMessage(rejected.reason()));
                }
                submissions.transition(orderId, "UNKNOWN", "RESERVATION_OUTCOME_PENDING");
                return pending(event);
            }
        });
    }

    private OrderResult result(OrderSubmissionStore.Submission row) {
        return switch (row.state()) {
            case "ACCEPTED" -> accepted(row.event());
            case "REJECTED" -> new OrderResult.Failed(422, row.reason(), rejectMessage(row.reason()));
            case "CANCELLED" -> new OrderResult.Failed(409, "ORDER_CANCELLED", "submission cancelled before acceptance");
            default -> pending(row.event());
        };
    }

    private static void afterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { action.run(); }
                    });
        } else {
            action.run();
        }
    }

    private static OrderResult accepted(OrderAcceptedEvent event) {
        return new OrderResult.Accepted(new OrderResult.AcceptedBody(event.getOrderId(), event.getEventId(),
                "ACCEPTED", event.getReservedAmountJpy(), event.getAcceptedAt()));
    }

    private static OrderResult pending(OrderAcceptedEvent event) {
        return new OrderResult.Pending(new OrderResult.PendingBody(event.getOrderId(), event.getEventId(),
                "RESERVATION_OUTCOME_PENDING", "UNKNOWN", "/orders/" + event.getOrderId(),
                "query this submission; do not submit a new order or release its reservation"));
    }

    private static String fingerprint(ValidatedOrder order) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : new Object[] {order.accountId(), order.symbol(), order.side(), order.orderType(),
                order.quantity(), order.limitPrice(), order.demoScenarioId()}) {
            if (value == null) {
                canonical.append('N');
            } else {
                String text = value.toString();
                canonical.append('V').append(text.length()).append(':').append(text);
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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
