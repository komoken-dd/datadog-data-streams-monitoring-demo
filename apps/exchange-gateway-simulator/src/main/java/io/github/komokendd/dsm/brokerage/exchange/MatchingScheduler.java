package io.github.komokendd.dsm.brokerage.exchange;

import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.common.JdbcEventOutbox;
import io.github.komokendd.dsm.brokerage.common.DurableKafkaSender;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Thin adapter around {@link MatchingEngine}: load the book, match, then persist the book and immutable
 * output events in one transaction. A separate relay publishes only committed events. Executions start from the scheduler, as a real exchange drop-copy feed would arrive
 * independently of the customer's order request.
 */
@Component
class MatchingScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(MatchingScheduler.class);

    private final MatchingEngine engine;
    private final WorkingOrderRepository repository;
    private final JdbcEventOutbox outbox;
    private final DurableKafkaSender sender;
    private final TransactionTemplate transactions;
    private final ExchangeMetrics metrics;
    private final ExchangeProperties properties;
    private final Clock clock;

    MatchingScheduler(
            MatchingEngine engine,
            WorkingOrderRepository repository,
            JdbcEventOutbox outbox,
            DurableKafkaSender sender,
            TransactionTemplate transactions,
            ExchangeMetrics metrics,
            ExchangeProperties properties,
            Clock clock) {
        this.engine = engine;
        this.repository = repository;
        this.outbox = outbox;
        this.sender = sender;
        this.transactions = transactions;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${demo.exchange.match-interval-ms:250}")
    void matchingTick() {
        try {
            TickResult result = transactions.execute(status -> {
                // The fence covers reading the book, generating fills, updating it,
                // and storing every output. No event is sent before this commits.
                outbox.lockWriter("matching");
                List<WorkingOrder> book = repository.loadWorking(properties.matchBatchSize());
                TickResult matched = engine.tick(book, clock.instant());
                repository.apply(matched.updates());
                stage(matched);
                return matched;
            });
            if (result == null) return;
            result.fills().forEach(metrics::recordFill);
            result.expiries().forEach(metrics::recordExpiry);
            if (result.fills().size() + result.expiries().size() >= 100) {
                LOG.info("Matching tick committed {} executions and {} expiries",
                        result.fills().size(), result.expiries().size());
            }
        } catch (RuntimeException exception) {
            LOG.error("Matching transaction failed; no uncommitted execution was published", exception);
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

    @Scheduled(fixedDelayString = "${demo.exchange.outbox-relay-ms:100}")
    void relayOutbox() {
        try {
            transactions.executeWithoutResult(status -> outbox.relay(sender, 250));
        } catch (RuntimeException exception) {
            LOG.error("Execution outbox not confirmed; immutable events remain pending", exception);
        }
    }

    private void stage(TickResult result) {
        for (TickResult.Fill fill : result.fills()) {
            ExecutionReportEvent event = ExchangeEvents.executionReport(fill);
            try (var ignored = EventLogContext.open(
                    event.getAccountId(), event.getOrderId(), event.getEventId(), event.getEventType(),
                    event.getSymbol(), event.getDemoScenarioId())
                    .attribute("execution_id", event.getExecutionId())
                    .attribute("execution_sequence", event.getExecutionSequence())
                    .attribute("order_status", event.getOrderStatus())) {
                outbox.append(event.getEventId(), Topics.EXECUTIONS, event.getAccountId(), event);
                LOG.info("Execution staged: {} {} @ {} (cumulative {}/{}, {}{})",
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
                outbox.append(event.getEventId(), Topics.ORDERS_EXPIRED, event.getAccountId(), event);
                LOG.info("Expiry staged: {} of {} shares unfilled ({})",
                        event.getExpiredQuantity(), event.getQuantity(), event.getReason());
            }
        }
    }
}
