package io.github.komokendd.dsm.brokerage.buyingpower;

import io.github.komokendd.dsm.brokerage.common.EventIds;
import io.github.komokendd.dsm.brokerage.common.IdempotentEventStore;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.BuyingPowerUpdatedEvent;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies executions and expiries to buying power. Each event is claimed, applied and published in
 * one database transaction: a publish failure rolls back so the redelivered record is reapplied, and
 * a redelivery after commit is skipped by the claim. The output event id is derived from the input,
 * so a publish that succeeded before a failed commit is deduplicated downstream.
 */
@Component
class BuyingPowerLedger {
    private static final Logger LOG = LoggerFactory.getLogger(BuyingPowerLedger.class);
    static final String EVENT_TYPE = "BUYING_POWER_UPDATED";

    private final BuyingPowerRepository repository;
    private final IdempotentEventStore processedEvents;
    private final KafkaTemplate<String, BuyingPowerUpdatedEvent> kafka;
    private final Clock clock;

    BuyingPowerLedger(
            BuyingPowerRepository repository,
            IdempotentEventStore processedEvents,
            KafkaTemplate<String, BuyingPowerUpdatedEvent> kafka,
            Clock clock) {
        this.repository = repository;
        this.processedEvents = processedEvents;
        this.kafka = kafka;
        this.clock = clock;
    }

    @Transactional
    public Optional<BuyingPowerUpdatedEvent> applyExecution(ExecutionReportEvent execution) {
        if (!processedEvents.claim(execution.getEventId())) {
            LOG.info("Duplicate execution report skipped");
            return Optional.empty();
        }
        var reservation = repository.lockReservation(execution.getOrderId()).orElse(null);
        if (reservation == null) {
            throw new IllegalStateException("Execution has no known reservation; reconciliation required");
        }
        if (!reservation.accountId().equals(execution.getAccountId())
                || !reservation.symbol().equals(execution.getSymbol())) {
            throw new IllegalArgumentException("Execution identity does not match reservation");
        }
        if (execution.getQuantity() != reservation.quantity()
                || execution.getCumulativeQuantity() != reservation.reflectedQuantity() + execution.getExecutedQuantity()
                || execution.getLeavesQuantity() != reservation.quantity() - execution.getCumulativeQuantity()) {
            throw new IllegalArgumentException("Execution progress is missing or inconsistent");
        }
        var delta = BuyingPowerRules.fill(
                Side.parse(execution.getSide()),
                reservation,
                execution.getExecutedQuantity(),
                execution.getLeavesQuantity(),
                execution.getExecutionAmountJpy());
        return apply(
                execution.getAccountId(),
                execution.getOrderId(),
                execution.getSymbol(),
                execution.getSide(),
                execution.getDemoScenarioId(),
                execution.getEventId(),
                execution.getEventType(),
                execution.getExecutedAt(),
                delta);
    }

    @Transactional
    public Optional<BuyingPowerUpdatedEvent> applyExpiry(OrderExpiredEvent expiry) {
        if (!processedEvents.claim(expiry.getEventId())) {
            LOG.info("Duplicate order expiry skipped");
            return Optional.empty();
        }
        var reservation = repository.lockReservation(expiry.getOrderId()).orElse(null);
        if (reservation == null) {
            LOG.warn("Expiry for an order without a reservation; nothing to release");
        }
        if (reservation == null || !reservation.accountId().equals(expiry.getAccountId())
                || !reservation.symbol().equals(expiry.getSymbol())
                || !reservation.side().name().equals(expiry.getSide())
                || expiry.getQuantity() != reservation.quantity()
                || expiry.getCumulativeQuantity() + expiry.getExpiredQuantity() != reservation.quantity()) {
            throw new IllegalArgumentException("Expiry does not match reservation");
        }
        return apply(
                expiry.getAccountId(),
                expiry.getOrderId(),
                expiry.getSymbol(),
                expiry.getSide(),
                expiry.getDemoScenarioId(),
                expiry.getEventId(),
                expiry.getEventType(),
                expiry.getExpiredAt(),
                BuyingPowerRules.expire(reservation, expiry.getExpiredQuantity()));
    }

    private Optional<BuyingPowerUpdatedEvent> apply(
            String accountId,
            String orderId,
            String symbol,
            String side,
            String demoScenarioId,
            String sourceEventId,
            String sourceEventType,
            Instant sourceOccurredAt,
            LedgerDelta delta) {
        if (!repository.applyToAccount(accountId, delta)) {
            throw new IllegalStateException("Event account is unknown; reconciliation required");
        }
        repository.applyToSellable(accountId, symbol, delta);
        repository.applyToReservation(orderId, delta);
        var account = repository.findAccount(accountId).orElseThrow();
        Instant reflectedAt = clock.instant();
        var event = BuyingPowerUpdatedEvent.newBuilder()
                .setEventId(EventIds.derived(sourceEventId, EVENT_TYPE))
                .setEventType(EVENT_TYPE)
                .setEventVersion(1)
                .setOccurredAt(reflectedAt)
                .setAccountId(accountId)
                .setOrderId(orderId)
                .setSymbol(symbol)
                .setSide(side)
                .setDemoScenarioId(demoScenarioId)
                .setSourceEventId(sourceEventId)
                .setSourceEventType(sourceEventType)
                .setSourceOccurredAt(sourceOccurredAt)
                .setCashBalanceJpy(account.cashJpy().setScale(0, RoundingMode.UNNECESSARY))
                .setReservedAmountJpy(account.reservedJpy().setScale(0, RoundingMode.UNNECESSARY))
                .setBuyingPowerJpy(account.buyingPowerJpy())
                .setReflectedAt(reflectedAt)
                .build();
        kafka.send(new ProducerRecord<>(Topics.BUYING_POWER_UPDATED, accountId, event)).join();
        return Optional.of(event);
    }
}
