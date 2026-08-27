package io.github.komokendd.dsm.fx.settlement;

import io.github.komokendd.dsm.fx.common.EventLogContext;
import io.github.komokendd.dsm.fx.common.EventIds;
import io.github.komokendd.dsm.fx.common.IdempotentEventStore;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.SettlementInstructedEvent;
import io.github.komokendd.dsm.fx.schema.TradeConfirmedEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class SettlementProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(SettlementProcessor.class);
    private final KafkaTemplate<String, SettlementInstructedEvent> kafka;
    private final IdempotentEventStore events;

    SettlementProcessor(KafkaTemplate<String, SettlementInstructedEvent> kafka, IdempotentEventStore settlementEventStore) {
        this.kafka = kafka;
        this.events = settlementEventStore;
    }

    @KafkaListener(
            topics = Topics.TRADES_CONFIRMED,
            groupId = "settlement-service-v1",
            containerFactory = "confirmedKafkaListenerFactory")
    @Transactional
    void onConfirmation(TradeConfirmedEvent trade) {
        if (!events.claim(trade.getEventId())) {
            return;
        }
        Instant instructedAt = Instant.now();
        String eventId = EventIds.derived(trade.getEventId(), "SETTLEMENT_INSTRUCTED");
        var instructed = SettlementInstructedEvent.newBuilder()
                .setEventId(eventId)
                .setOrderId(trade.getOrderId())
                .setEventType("SETTLEMENT_INSTRUCTED")
                .setEventVersion(1)
                .setOccurredAt(instructedAt)
                .setCurrencyPair(trade.getCurrencyPair())
                .setDemoScenarioId(trade.getDemoScenarioId())
                .setExecutionId(trade.getExecutionId())
                .setConfirmationId(trade.getConfirmationId())
                .setSettlementInstructionId(EventIds.derived(trade.getEventId(), "SETTLEMENT_INSTRUCTION"))
                .setSide(trade.getSide())
                .setQuantity(trade.getQuantity())
                .setNotionalUsd(trade.getNotionalUsd())
                .setSubmittedAt(trade.getSubmittedAt())
                .setInstructedAt(instructedAt)
                .setValueDate(LocalDate.now(ZoneOffset.UTC).plusDays(2).toString())
                .build();
        kafka.send(Topics.SETTLEMENTS_INSTRUCTED, trade.getOrderId(), instructed).join();
        try (var ignored = EventLogContext.open(
                trade.getOrderId(), eventId, "SETTLEMENT_INSTRUCTED",
                trade.getCurrencyPair(), trade.getDemoScenarioId())) {
            LOG.info("Synthetic settlement instruction emitted; no external settlement rail is called");
        }
    }
}
