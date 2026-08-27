package io.github.komokendd.dsm.fx.deadletter;

import io.github.komokendd.dsm.fx.common.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class DeadLetterMetrics {
    private final DeadLetterRepository repository;
    private final AtomicLong unresolved = new AtomicLong();
    private final AtomicReference<Double> notional = new AtomicReference<>(0.0);
    private final AtomicReference<Double> oldestUnresolvedAge = new AtomicReference<>(0.0);

    DeadLetterMetrics(DeadLetterRepository repository, MeterRegistry metrics) {
        this.repository = repository;
        Gauge.builder(MetricNames.DLT_UNRESOLVED, unresolved, AtomicLong::doubleValue).register(metrics);
        Gauge.builder(MetricNames.DLT_NOTIONAL, notional, AtomicReference::get).baseUnit("USD").register(metrics);
        Gauge.builder(MetricNames.DLT_OLDEST_UNRESOLVED_AGE, oldestUnresolvedAge, AtomicReference::get)
                .baseUnit("seconds")
                .description("Age of the oldest unresolved dead letter since it reached the DLT")
                .register(metrics);
    }

    @Scheduled(fixedDelay = 5000)
    void refresh() {
        var rows = repository.findUnresolved();
        unresolved.set(rows.size());
        notional.set(rows.stream().mapToDouble(row -> row.notionalUsd().doubleValue()).sum());
        Instant oldestReceivedAt = rows.stream()
                .map(UnresolvedDeadLetter::receivedAt)
                .min(Instant::compareTo)
                .orElse(null);
        oldestUnresolvedAge.set(oldestReceivedAt == null
                ? 0.0
                : (double) Math.max(0, Duration.between(oldestReceivedAt, Instant.now()).toSeconds()));
    }
}
