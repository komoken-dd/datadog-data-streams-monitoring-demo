package io.github.komokendd.dsm.brokerage.order;

import io.github.komokendd.dsm.brokerage.common.DurableKafkaSender;
import io.github.komokendd.dsm.brokerage.common.JdbcEventOutbox;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import java.time.Instant;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles original submissions and relays committed acceptances without creating replacement orders. */
@Component
class OrderRecoveryScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(OrderRecoveryScheduler.class);
    private final OrderIntakeService intake;
    private final OrderSubmissionStore submissions;
    private final JdbcEventOutbox outbox;
    private final DurableKafkaSender sender;
    private final TransactionTemplate transactions;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestPending = new AtomicLong();
    private final AtomicLong unpublished = new AtomicLong();
    private final AtomicLong oldestUnpublished = new AtomicLong();
    private final AtomicLong epochUsed = new AtomicLong();
    private final AtomicLong epochLimit = new AtomicLong();

    OrderRecoveryScheduler(OrderIntakeService intake, OrderSubmissionStore submissions, JdbcEventOutbox outbox,
            DurableKafkaSender sender, TransactionTemplate transactions, MeterRegistry metrics) {
        this.intake = intake;
        this.submissions = submissions;
        this.outbox = outbox;
        this.sender = sender;
        this.transactions = transactions;
        gauge(metrics, "demo.brokerage.order_submissions.pending", pending);
        gauge(metrics, "demo.brokerage.order_submissions.oldest_pending_age", oldestPending);
        gauge(metrics, "demo.brokerage.order_submissions.outbox.pending", unpublished);
        gauge(metrics, "demo.brokerage.order_submissions.outbox.oldest_pending_age", oldestUnpublished);
        gauge(metrics, "demo.brokerage.demo_epoch.submissions", epochUsed);
        gauge(metrics, "demo.brokerage.demo_epoch.limit", epochLimit);
    }

    private static void gauge(MeterRegistry registry, String name, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::doubleValue).register(registry);
    }

    @Scheduled(fixedDelay = 5000)
    void refreshRecoveryMetrics() {
        try {
            var summary = submissions.recoverySummary();
            var outgoing = outbox.pendingSummary();
            long[] budget = submissions.epochBudget();
            pending.set(summary.pending());
            oldestPending.set(age(summary.oldestCreatedAt()));
            unpublished.set(outgoing.count());
            oldestUnpublished.set(age(outgoing.oldestCreatedAt()));
            epochUsed.set(budget[0]);
            epochLimit.set(budget[1]);
        } catch (RuntimeException failure) {
            LOG.error("Order recovery metrics unavailable", failure);
        }
    }

    private static long age(Instant since) {
        return since == null ? 0 : Math.max(0, Duration.between(since, Instant.now()).toSeconds());
    }

    @Scheduled(fixedDelay = 1000)
    void reconcile() {
        try {
            for (String id : submissions.pending(25)) {
                try { intake.advance(id, false); }
                catch (RuntimeException failure) { LOG.error("Submission reconciliation failed, order_id={}", id, failure); }
            }
        } catch (RuntimeException failure) {
            LOG.error("Submission reconciliation unavailable", failure);
        }
    }

    @Scheduled(fixedDelay = 100)
    void publishCommittedAcceptances() {
        try {
            transactions.executeWithoutResult(status -> outbox.relay(sender, 250));
        } catch (RuntimeException failure) {
            LOG.error("Accepted-order outbox unconfirmed; original records remain pending", failure);
        }
    }
}
