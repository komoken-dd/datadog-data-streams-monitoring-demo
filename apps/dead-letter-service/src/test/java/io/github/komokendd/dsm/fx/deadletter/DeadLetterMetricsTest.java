package io.github.komokendd.dsm.fx.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.fx.common.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeadLetterMetricsTest {
    @Test
    void publishesCurrentUnresolvedVolumeAndOldestResidenceTime() {
        DeadLetterRepository repository = mock(DeadLetterRepository.class);
        Instant now = Instant.now();
        when(repository.findUnresolved()).thenReturn(List.of(
                unresolved("order-newer", new BigDecimal("100000.00"), now.minusSeconds(30)),
                unresolved("order-oldest", new BigDecimal("250000.00"), now.minusSeconds(120))));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeadLetterMetrics metrics = new DeadLetterMetrics(repository, registry);

        metrics.refresh();

        assertThat(registry.get(MetricNames.DLT_UNRESOLVED).gauge().value()).isEqualTo(2.0);
        assertThat(registry.get(MetricNames.DLT_NOTIONAL).gauge().value()).isEqualTo(350000.0);
        assertThat(registry.get(MetricNames.DLT_OLDEST_UNRESOLVED_AGE).gauge().value())
                .isBetween(119.0, 121.0);
    }

    @Test
    void resetsOldestResidenceTimeWhenNoDeadLettersRemain() {
        DeadLetterRepository repository = mock(DeadLetterRepository.class);
        when(repository.findUnresolved()).thenReturn(List.of());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeadLetterMetrics metrics = new DeadLetterMetrics(repository, registry);

        metrics.refresh();

        assertThat(registry.get(MetricNames.DLT_OLDEST_UNRESOLVED_AGE).gauge().value()).isZero();
    }

    private static UnresolvedDeadLetter unresolved(String orderId, BigDecimal notionalUsd, Instant receivedAt) {
        return new UnresolvedDeadLetter(
                "event-" + orderId,
                orderId,
                "USD/JPY",
                "BUY",
                notionalUsd,
                "scenario-1",
                3,
                "DEMO_PROCESSING_FAILURE",
                receivedAt.minusSeconds(1),
                receivedAt);
    }
}
