package io.github.komokendd.dsm.fx.pretrade;

import io.github.komokendd.dsm.fx.common.EventLogContext;
import io.github.komokendd.dsm.fx.common.EventIds;
import io.github.komokendd.dsm.fx.common.MetricNames;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.OrderApprovedEvent;
import io.github.komokendd.dsm.fx.schema.OrderReceivedEvent;
import io.github.komokendd.dsm.fx.schema.OrderRejectedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
final class PreTradeControl {
    private static final Logger LOG = LoggerFactory.getLogger(PreTradeControl.class);

    private final KafkaTemplate<String, Object> kafka;
    private final MeterRegistry metrics;
    private final BigDecimal maximumNotionalUsd;

    PreTradeControl(
            KafkaTemplate<String, Object> kafka,
            MeterRegistry metrics,
            @Value("${demo.controls.maximum-notional-usd:5000000.00}") BigDecimal maximumNotionalUsd) {
        this.kafka = kafka;
        this.metrics = metrics;
        this.maximumNotionalUsd = maximumNotionalUsd;
    }

    @KafkaListener(
            topics = Topics.ORDERS_RECEIVED,
            groupId = "pre-trade-control-service-v1",
            containerFactory = "receivedKafkaListenerFactory")
    void onOrder(OrderReceivedEvent order) {
        if (order.getNotionalUsd().compareTo(maximumNotionalUsd) > 0) {
            reject(order, "DEMO_MAX_NOTIONAL_EXCEEDED");
        } else {
            approve(order);
        }
    }

    private void approve(OrderReceivedEvent order) {
        Instant now = Instant.now();
        String eventId = EventIds.derived(order.getEventId(), "ORDER_APPROVED");
        var approved = OrderApprovedEvent.newBuilder()
                .setEventId(eventId)
                .setOrderId(order.getOrderId())
                .setEventType("ORDER_APPROVED")
                .setEventVersion(1)
                .setOccurredAt(now)
                .setCurrencyPair(order.getCurrencyPair())
                .setDemoScenarioId(order.getDemoScenarioId())
                .setSide(order.getSide())
                .setQuantity(order.getQuantity())
                .setLimitPrice(order.getLimitPrice())
                .setNotionalUsd(order.getNotionalUsd())
                .setSubmittedAt(order.getSubmittedAt())
                .setApprovedAt(now)
                .build();
        kafka.send(Topics.ORDERS_APPROVED, order.getOrderId(), approved).join();
        log(order, eventId, "ORDER_APPROVED", "Synthetic pre-trade control approved order");
    }

    private void reject(OrderReceivedEvent order, String reason) {
        Instant now = Instant.now();
        String eventId = EventIds.derived(order.getEventId(), "ORDER_REJECTED");
        var rejected = OrderRejectedEvent.newBuilder()
                .setEventId(eventId)
                .setOrderId(order.getOrderId())
                .setEventType("ORDER_REJECTED")
                .setEventVersion(1)
                .setOccurredAt(now)
                .setCurrencyPair(order.getCurrencyPair())
                .setDemoScenarioId(order.getDemoScenarioId())
                .setSide(order.getSide())
                .setQuantity(order.getQuantity())
                .setNotionalUsd(order.getNotionalUsd())
                .setSubmittedAt(order.getSubmittedAt())
                .setRejectedAt(now)
                .setRejectionReason(reason)
                .build();
        kafka.send(Topics.ORDERS_REJECTED, order.getOrderId(), rejected).join();
        Counter.builder(MetricNames.ORDERS_REJECTED)
                .tag("currency_pair", order.getCurrencyPair())
                .tag("failure_category", reason)
                .register(metrics)
                .increment();
        log(order, eventId, "ORDER_REJECTED", "Synthetic pre-trade control rejected order");
    }

    private void log(OrderReceivedEvent order, String eventId, String eventType, String message) {
        try (var ignored = EventLogContext.open(
                order.getOrderId(), eventId, eventType, order.getCurrencyPair(), order.getDemoScenarioId())) {
            LOG.info(message);
        }
    }
}
