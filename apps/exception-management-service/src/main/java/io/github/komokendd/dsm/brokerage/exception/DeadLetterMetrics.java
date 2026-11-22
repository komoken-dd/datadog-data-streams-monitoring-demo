package io.github.komokendd.dsm.brokerage.exception;

import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Current open-exception gauges, read from the table so they survive restarts. */
@Component
class DeadLetterMetrics {
    private final NotificationDeadLetterRepository repository;
    private final Clock clock;
    private final AtomicLong unresolved = new AtomicLong();
    private final AtomicLong oldestUnresolvedAgeSeconds = new AtomicLong();

    DeadLetterMetrics(NotificationDeadLetterRepository repository, MeterRegistry metrics, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        Gauge.builder(MetricNames.NOTIFICATION_DLT_UNRESOLVED, unresolved, AtomicLong::doubleValue)
                .description("Customer notifications in the DLT that have not been redriven")
                .register(metrics);
        Gauge.builder(MetricNames.NOTIFICATION_DLT_OLDEST_AGE, oldestUnresolvedAgeSeconds, AtomicLong::doubleValue)
                .baseUnit("seconds")
                .description("Age of the oldest unresolved notification dead letter since it reached the DLT")
                .register(metrics);
    }

    @Scheduled(fixedDelay = 5000)
    void refresh() {
        var summary = repository.unresolvedSummary();
        unresolved.set(summary.count());
        oldestUnresolvedAgeSeconds.set(summary.oldestReceivedAt() == null
                ? 0
                : Math.max(0, Duration.between(summary.oldestReceivedAt(), clock.instant()).toSeconds()));
    }
}
