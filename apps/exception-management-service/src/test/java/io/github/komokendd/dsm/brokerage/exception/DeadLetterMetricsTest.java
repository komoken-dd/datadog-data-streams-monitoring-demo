package io.github.komokendd.dsm.brokerage.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DeadLetterMetricsTest {
    private static final Instant NOW = Instant.parse("2026-11-20T00:10:00Z");

    @Test
    void publishesUnresolvedCountAndOldestResidenceTime() {
        var repository = mock(NotificationDeadLetterRepository.class);
        when(repository.unresolvedSummary())
                .thenReturn(new NotificationDeadLetterRepository.UnresolvedSummary(42, NOW.minusSeconds(120)));
        var registry = new SimpleMeterRegistry();
        var metrics = new DeadLetterMetrics(repository, registry, Clock.fixed(NOW, ZoneOffset.UTC));

        metrics.refresh();

        assertThat(registry.get(MetricNames.NOTIFICATION_DLT_UNRESOLVED).gauge().value()).isEqualTo(42.0);
        assertThat(registry.get(MetricNames.NOTIFICATION_DLT_OLDEST_AGE).gauge().value()).isEqualTo(120.0);
    }

    @Test
    void resetsToZeroOnceEverythingIsRedriven() {
        var repository = mock(NotificationDeadLetterRepository.class);
        when(repository.unresolvedSummary())
                .thenReturn(new NotificationDeadLetterRepository.UnresolvedSummary(3, NOW.minusSeconds(60)))
                .thenReturn(new NotificationDeadLetterRepository.UnresolvedSummary(0, null));
        var registry = new SimpleMeterRegistry();
        var metrics = new DeadLetterMetrics(repository, registry, Clock.fixed(NOW, ZoneOffset.UTC));

        metrics.refresh();
        metrics.refresh();

        assertThat(registry.get(MetricNames.NOTIFICATION_DLT_UNRESOLVED).gauge().value()).isZero();
        assertThat(registry.get(MetricNames.NOTIFICATION_DLT_OLDEST_AGE).gauge().value()).isZero();
    }
}
