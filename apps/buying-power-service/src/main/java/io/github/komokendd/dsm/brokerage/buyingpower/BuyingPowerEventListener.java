package io.github.komokendd.dsm.brokerage.buyingpower;

import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.BuyingPowerUpdatedEvent;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
final class BuyingPowerEventListener {
    private static final Logger LOG = LoggerFactory.getLogger(BuyingPowerEventListener.class);
    static final String GROUP_ID = "buying-power-service-v1";
    static final String SOURCE_EXECUTION = "execution";
    static final String SOURCE_EXPIRY = "expiry";

    private final BuyingPowerLedger ledger;
    private final ReflectionControl control;
    private final MeterRegistry metrics;

    BuyingPowerEventListener(BuyingPowerLedger ledger, ReflectionControl control, MeterRegistry metrics) {
        this.ledger = ledger;
        this.control = control;
        this.metrics = metrics;
    }

    @KafkaListener(topics = Topics.EXECUTIONS, groupId = GROUP_ID, containerFactory = "executionListenerFactory")
    void onExecution(ExecutionReportEvent execution) {
        try (var ignored = EventLogContext.open(
                        execution.getAccountId(),
                        execution.getOrderId(),
                        execution.getEventId(),
                        execution.getEventType(),
                        execution.getSymbol(),
                        execution.getDemoScenarioId())
                .attribute("side", execution.getSide())
                .attribute("execution_id", execution.getExecutionId())
                .attribute("execution_sequence", execution.getExecutionSequence())) {
            // Outside the transaction so a slowed consumer does not hold database connections.
            control.pause();
            record(ledger.applyExecution(execution), execution.getSide(), SOURCE_EXECUTION);
        }
    }

    @KafkaListener(topics = Topics.ORDERS_EXPIRED, groupId = GROUP_ID, containerFactory = "expiryListenerFactory")
    void onExpiry(OrderExpiredEvent expiry) {
        try (var ignored = EventLogContext.open(
                        expiry.getAccountId(),
                        expiry.getOrderId(),
                        expiry.getEventId(),
                        expiry.getEventType(),
                        expiry.getSymbol(),
                        expiry.getDemoScenarioId())
                .attribute("side", expiry.getSide())
                .attribute("expiry_reason", expiry.getReason())) {
            control.pause();
            record(ledger.applyExpiry(expiry), expiry.getSide(), SOURCE_EXPIRY);
        }
    }

    private void record(Optional<BuyingPowerUpdatedEvent> published, String side, String source) {
        if (published.isEmpty()) {
            return;
        }
        var event = published.get();
        long delayMillis = Math.max(0, Duration.between(event.getSourceOccurredAt(), event.getReflectedAt()).toMillis());
        DistributionSummary.builder(MetricNames.BUYING_POWER_REFLECTION_DELAY)
                .baseUnit("milliseconds")
                .publishPercentileHistogram()
                .tag("side", side)
                .tag("source", source)
                .register(metrics)
                .record(delayMillis);
        Counter.builder(MetricNames.BUYING_POWER_UPDATES)
                .tag("side", side)
                .tag("source", source)
                .register(metrics)
                .increment();
        LOG.info("Buying power updated, reflection_delay_ms={}, cash_balance_jpy={}, reserved_amount_jpy={},"
                        + " buying_power_jpy={}",
                delayMillis,
                event.getCashBalanceJpy().toPlainString(),
                event.getReservedAmountJpy().toPlainString(),
                event.getBuyingPowerJpy().toPlainString());
    }
}
