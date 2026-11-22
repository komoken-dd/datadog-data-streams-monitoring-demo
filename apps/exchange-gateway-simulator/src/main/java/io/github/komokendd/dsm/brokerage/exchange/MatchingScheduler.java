package io.github.komokendd.dsm.brokerage.exchange;

import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Thin adapter around {@link MatchingEngine}: load the book, match, then persist and publish in one
 * transaction. Executions start from the scheduler, as a real exchange drop-copy feed would arrive
 * independently of the customer's order request.
 */
@Component
class MatchingScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(MatchingScheduler.class);

    private final MatchingEngine engine;
    private final WorkingOrderRepository repository;
    private final KafkaTemplate<String, SpecificRecord> kafka;
    private final TransactionTemplate transactions;
    private final ExchangeMetrics metrics;
    private final ExchangeProperties properties;
    private final Clock clock;

    MatchingScheduler(
            MatchingEngine engine,
            WorkingOrderRepository repository,
            KafkaTemplate<String, SpecificRecord> kafka,
            TransactionTemplate transactions,
            ExchangeMetrics metrics,
            ExchangeProperties properties,
            Clock clock) {
        this.engine = engine;
        this.repository = repository;
        this.kafka = kafka;
        this.transactions = transactions;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${demo.exchange.match-interval-ms:250}")
    void matchingTick() {
        try {
            List<WorkingOrder> book = repository.loadWorking(properties.matchBatchSize());
            TickResult result = engine.tick(book, clock.instant());
            if (result.isEmpty()) {
                return;
            }
            transactions.executeWithoutResult(status -> {
                repository.apply(result.updates());
                publish(result);
            });
            result.fills().forEach(metrics::recordFill);
            result.expiries().forEach(metrics::recordExpiry);
            if (result.fills().size() + result.expiries().size() >= 100) {
                LOG.info("Matching tick published {} executions and {} expiries",
                        result.fills().size(), result.expiries().size());
            }
        } catch (RuntimeException exception) {
            LOG.error("Matching tick failed; the book is retried on the next tick", exception);
        }
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 1_000)
    void refreshWorkingOrderGauge() {
        try {
            metrics.workingOrders(repository.countWorkingBySymbol());
        } catch (RuntimeException exception) {
            LOG.warn("Working-order gauge refresh failed: {}", exception.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void purgeCompletedOrders() {
        try {
            Instant cutoff = clock.instant().minus(Duration.ofMinutes(properties.completedRetentionMinutes()));
            int purged = repository.purgeCompletedBefore(cutoff);
            if (purged > 0) {
                LOG.debug("Purged {} completed exchange orders", purged);
            }
        } catch (RuntimeException exception) {
            LOG.warn("Completed-order purge failed: {}", exception.getMessage());
        }
    }

    private void publish(TickResult result) {
        List<CompletableFuture<?>> sends = new ArrayList<>(result.fills().size() + result.expiries().size());
        for (TickResult.Fill fill : result.fills()) {
            ExecutionReportEvent event = ExchangeEvents.executionReport(fill);
            try (var ignored = EventLogContext.open(
                    event.getAccountId(), event.getOrderId(), event.getEventId(), event.getEventType(),
                    event.getSymbol(), event.getDemoScenarioId())
                    .attribute("execution_id", event.getExecutionId())
                    .attribute("execution_sequence", event.getExecutionSequence())
                    .attribute("order_status", event.getOrderStatus())) {
                sends.add(kafka.send(Topics.EXECUTIONS, event.getAccountId(), event));
                LOG.info("Execution reported: {} {} @ {} (cumulative {}/{}, {}{})",
                        event.getSide(), event.getExecutedQuantity(), event.getExecutionPrice(),
                        event.getCumulativeQuantity(), event.getQuantity(), event.getOrderStatus(),
                        fill.openingAuction() ? ", opening auction" : "");
            }
        }
        for (TickResult.Expiry expiry : result.expiries()) {
            OrderExpiredEvent event = ExchangeEvents.orderExpired(expiry);
            try (var ignored = EventLogContext.open(
                    event.getAccountId(), event.getOrderId(), event.getEventId(), event.getEventType(),
                    event.getSymbol(), event.getDemoScenarioId())
                    .attribute("side", event.getSide())
                    .attribute("reason", event.getReason())) {
                sends.add(kafka.send(Topics.ORDERS_EXPIRED, event.getAccountId(), event));
                LOG.info("Order expired: {} of {} shares unfilled ({})",
                        event.getExpiredQuantity(), event.getQuantity(), event.getReason());
            }
        }
        // Wait for every broker ack before committing the book update.
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).join();
    }
}
