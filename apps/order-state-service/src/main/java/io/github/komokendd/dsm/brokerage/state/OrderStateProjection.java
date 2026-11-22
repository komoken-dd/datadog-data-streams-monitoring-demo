package io.github.komokendd.dsm.brokerage.state;

import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.KafkaHeaders;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.BuyingPowerUpdatedEvent;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.NotificationDeliveredEvent;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import io.github.komokendd.dsm.brokerage.schema.TradeReportIssuedEvent;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Business view of every order: status, fills, and whether each fill reached the customer. */
@Component
@Transactional
class OrderStateProjection {
    static final String GROUP_ID = "order-state-service-v1";
    /** Expiries have no execution row; their downstream outcomes are not projected per execution. */
    static final String EXPIRY_EVENT_TYPE = "ORDER_EXPIRED";
    static final String EXPIRY_NOTIFICATION_TYPE = "EXPIRY";
    private static final Logger LOG = LoggerFactory.getLogger(OrderStateProjection.class);
    private final OrderStateStore store;

    OrderStateProjection(OrderStateStore store) {
        this.store = store;
    }

    @KafkaListener(topics = Topics.ORDERS_ACCEPTED, groupId = GROUP_ID, containerFactory = "stateAcceptedFactory")
    void accepted(OrderAcceptedEvent event) {
        try (var log = open(event.getAccountId(), event.getOrderId(), event.getEventId(), event.getEventType(),
                event.getSymbol(), event.getDemoScenarioId())) {
            if (!store.claim(event.getEventId(), "accepted")) {
                LOG.info("Duplicate order accepted event ignored");
                return;
            }
            logTransition(log, store.applyOrder(EventSnapshots.accepted(event)));
        }
    }

    @KafkaListener(topics = Topics.EXECUTIONS, groupId = GROUP_ID, containerFactory = "stateExecutionFactory")
    void executed(ExecutionReportEvent event) {
        try (var log = open(event.getAccountId(), event.getOrderId(), event.getEventId(), event.getEventType(),
                event.getSymbol(), event.getDemoScenarioId())) {
            if (!store.claim(event.getEventId(), "execution")) {
                LOG.info("Duplicate execution ignored by order state");
                return;
            }
            var transition = store.applyOrder(EventSnapshots.execution(event));
            int parked = store.recordExecution(EventSnapshots.executionRecord(event));
            log.attribute("execution_id", event.getExecutionId())
                    .attribute("execution_sequence", event.getExecutionSequence())
                    .attribute("executed_quantity", event.getExecutedQuantity())
                    .attribute("applied_early_marks", Math.max(parked, 0));
            logTransition(log, transition);
        }
    }

    @KafkaListener(topics = Topics.ORDERS_EXPIRED, groupId = GROUP_ID, containerFactory = "stateExpiredFactory")
    void expired(OrderExpiredEvent event) {
        try (var log = open(event.getAccountId(), event.getOrderId(), event.getEventId(), event.getEventType(),
                event.getSymbol(), event.getDemoScenarioId())) {
            if (!store.claim(event.getEventId(), "expired")) {
                LOG.info("Duplicate order expiry ignored");
                return;
            }
            log.attribute("reason", event.getReason()).attribute("expired_quantity", event.getExpiredQuantity());
            logTransition(log, store.applyOrder(EventSnapshots.expired(event)));
        }
    }

    @KafkaListener(topics = Topics.BUYING_POWER_UPDATED, groupId = GROUP_ID,
            containerFactory = "stateBuyingPowerFactory")
    void buyingPowerUpdated(BuyingPowerUpdatedEvent event) {
        try (var log = open(event.getAccountId(), event.getOrderId(), event.getEventId(), event.getEventType(),
                event.getSymbol(), event.getDemoScenarioId())) {
            if (EXPIRY_EVENT_TYPE.equals(event.getSourceEventType())
                    || !store.claim(event.getEventId(), "buying-power")) {
                return;
            }
            mark(log, event.getOrderId(), event.getSourceEventId(),
                    new ExecutionMark(ExecutionMark.Type.BUYING_POWER_REFLECTED, event.getReflectedAt(), false));
        }
    }

    @KafkaListener(topics = Topics.TRADE_REPORTS_ISSUED, groupId = GROUP_ID,
            containerFactory = "stateTradeReportFactory")
    void tradeReportIssued(TradeReportIssuedEvent event) {
        try (var log = open(event.getAccountId(), event.getOrderId(), event.getEventId(), event.getEventType(),
                event.getSymbol(), event.getDemoScenarioId())) {
            if (!store.claim(event.getEventId(), "trade-report")) {
                return;
            }
            log.attribute("execution_id", event.getExecutionId())
                    .attribute("trade_date", event.getTradeDate())
                    .attribute("settlement_date", event.getSettlementDate());
            mark(log, event.getOrderId(), event.getExecutionId(),
                    new ExecutionMark(ExecutionMark.Type.TRADE_REPORT_ISSUED, event.getIssuedAt(), false));
        }
    }

    @KafkaListener(topics = Topics.NOTIFICATIONS_DELIVERED, groupId = GROUP_ID,
            containerFactory = "stateDeliveredFactory")
    void notificationDelivered(NotificationDeliveredEvent event) {
        try (var log = open(event.getAccountId(), event.getOrderId(), event.getEventId(), event.getEventType(),
                event.getSymbol(), event.getDemoScenarioId())) {
            if (EXPIRY_NOTIFICATION_TYPE.equals(event.getNotificationType())
                    || !store.claim(event.getEventId(), "notification-delivered")) {
                return;
            }
            log.attribute("notification_id", event.getNotificationId())
                    .attribute("attempts", event.getAttempts())
                    .attribute("redriven", event.getRedriven());
            mark(log, event.getOrderId(), event.getSourceEventId(),
                    new ExecutionMark(ExecutionMark.Type.NOTIFICATION_DELIVERED, event.getDeliveredAt(),
                            event.getRedriven()));
        }
    }

    @KafkaListener(topics = Topics.NOTIFICATIONS_RETRY_5S, groupId = GROUP_ID,
            containerFactory = "stateNotificationRequestFactory")
    void notificationRetryFive(ConsumerRecord<String, NotificationRequest> record) {
        notificationFailure(record, "retry-5s", ExecutionMark.Type.NOTIFICATION_RETRYING);
    }

    @KafkaListener(topics = Topics.NOTIFICATIONS_RETRY_30S, groupId = GROUP_ID,
            containerFactory = "stateNotificationRequestFactory")
    void notificationRetryThirty(ConsumerRecord<String, NotificationRequest> record) {
        notificationFailure(record, "retry-30s", ExecutionMark.Type.NOTIFICATION_RETRYING);
    }

    @KafkaListener(topics = Topics.NOTIFICATIONS_DLT, groupId = GROUP_ID,
            containerFactory = "stateNotificationRequestFactory")
    void notificationDeadLetter(ConsumerRecord<String, NotificationRequest> record) {
        notificationFailure(record, "dlt", ExecutionMark.Type.NOTIFICATION_DEAD_LETTER);
    }

    private void notificationFailure(
            ConsumerRecord<String, NotificationRequest> record, String stage, ExecutionMark.Type type) {
        NotificationRequest request = record.value();
        int retryCount = retryCount(record);
        boolean redriven = headerBoolean(record, KafkaHeaders.REDRIVEN);
        try (var log = open(request.getAccountId(), request.getOrderId(), request.getEventId(),
                request.getEventType(), request.getSymbol(), request.getDemoScenarioId()).retry(retryCount)) {
            // The same request id travels through every retry hop, so the hop is part of the key.
            String transitionKey = stage + "-" + retryCount + (redriven ? "-redriven" : "");
            if (EXPIRY_NOTIFICATION_TYPE.equals(request.getNotificationType())
                    || !store.claim(request.getEventId(), transitionKey)) {
                return;
            }
            log.attribute("notification_id", request.getNotificationId())
                    .attribute("notification_stage", stage)
                    .attribute("redriven", redriven);
            mark(log, request.getOrderId(), request.getSourceEventId(), new ExecutionMark(type, null, redriven));
        }
    }

    private void mark(EventLogContext log, String orderId, String matchValue, ExecutionMark mark) {
        var result = store.applyMark(orderId, matchValue, mark);
        log.attribute("mark", mark.type().name());
        if (!result.applied()) {
            LOG.info("Execution outcome arrived before its execution report; parked until the execution arrives");
            return;
        }
        log.attribute("notification_status", result.progress().notificationStatus().name())
                .attribute("buying_power_reflected", result.progress().buyingPowerReflectedAt() != null)
                .attribute("trade_report_issued", result.progress().tradeReportIssuedAt() != null);
        LOG.info("Execution outcome projected: {}", mark.type());
    }

    private static void logTransition(EventLogContext log, OrderStateStore.OrderTransition transition) {
        OrderSnapshot order = transition.order();
        log.attribute("previous_status", transition.previous() == null ? "" : transition.previous().name())
                .attribute("status", order.status().name())
                .attribute("side", order.side())
                .attribute("quantity", order.quantity())
                .attribute("cumulative_quantity", order.cumulativeQuantity());
        if (transition.previous() != order.status()) {
            LOG.info("Order status {} -> {}", transition.previous(), order.status());
        } else {
            LOG.info("Order event applied, status remains {}", order.status());
        }
    }

    private static EventLogContext open(
            String accountId, String orderId, String eventId, String eventType, String symbol, String scenarioId) {
        return EventLogContext.open(accountId, orderId, eventId, eventType, symbol, scenarioId);
    }

    static int retryCount(ConsumerRecord<?, ?> record) {
        var header = record.headers().lastHeader(KafkaHeaders.RETRY_COUNT);
        if (header == null) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    static boolean headerBoolean(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header != null && Boolean.parseBoolean(new String(header.value(), StandardCharsets.UTF_8).trim());
    }
}
