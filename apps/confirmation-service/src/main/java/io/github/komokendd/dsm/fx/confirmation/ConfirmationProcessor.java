package io.github.komokendd.dsm.fx.confirmation;

import io.github.komokendd.dsm.fx.common.EventLogContext;
import io.github.komokendd.dsm.fx.common.EventIds;
import io.github.komokendd.dsm.fx.common.IdempotentEventStore;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.TradeConfirmedEvent;
import io.github.komokendd.dsm.fx.schema.TradeExecutedEvent;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ConfirmationProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(ConfirmationProcessor.class);
    private final KafkaTemplate<String, TradeConfirmedEvent> kafka;
    private final IdempotentEventStore events;

    ConfirmationProcessor(KafkaTemplate<String, TradeConfirmedEvent> kafka, IdempotentEventStore confirmationEventStore) {
        this.kafka = kafka;
        this.events = confirmationEventStore;
    }

    @KafkaListener(
            topics = Topics.TRADES_EXECUTED,
            groupId = "confirmation-service-v1",
            containerFactory = "confirmationKafkaListenerFactory")
    @Transactional
    void onTrade(TradeExecutedEvent trade) {
        if (!events.claim(trade.getEventId())) {
            return;
        }
        Instant confirmedAt = Instant.now();
        String eventId = EventIds.derived(trade.getEventId(), "TRADE_CONFIRMED");
        var confirmed = TradeConfirmedEvent.newBuilder()
                .setEventId(eventId)
                .setOrderId(trade.getOrderId())
                .setEventType("TRADE_CONFIRMED")
                .setEventVersion(1)
                .setOccurredAt(confirmedAt)
                .setCurrencyPair(trade.getCurrencyPair())
                .setDemoScenarioId(trade.getDemoScenarioId())
                .setExecutionId(trade.getExecutionId())
                .setConfirmationId(EventIds.derived(trade.getEventId(), "CONFIRMATION"))
                .setSide(trade.getSide())
                .setQuantity(trade.getQuantity())
                .setExecutionPrice(trade.getExecutionPrice())
                .setNotionalUsd(trade.getNotionalUsd())
                .setSubmittedAt(trade.getSubmittedAt())
                .setExecutedAt(trade.getExecutedAt())
                .setConfirmedAt(confirmedAt)
                .build();
        kafka.send(Topics.TRADES_CONFIRMED, trade.getOrderId(), confirmed).join();
        try (var ignored = EventLogContext.open(
                trade.getOrderId(), eventId, "TRADE_CONFIRMED", trade.getCurrencyPair(), trade.getDemoScenarioId())) {
            LOG.info("Synthetic trade confirmation emitted");
        }
    }
}
