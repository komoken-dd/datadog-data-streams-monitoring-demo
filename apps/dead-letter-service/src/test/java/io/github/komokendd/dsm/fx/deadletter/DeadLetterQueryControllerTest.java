package io.github.komokendd.dsm.fx.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeadLetterQueryControllerTest {
    @Test
    void returnsTheAuthoritativeCurrentUnresolvedBreakdown() {
        DeadLetterRepository repository = mock(DeadLetterRepository.class);
        Instant now = Instant.now();
        when(repository.findUnresolved()).thenReturn(List.of(
                row("event-1", "order-1", "1000000.00", now),
                row("event-2", "order-2", "250000.00", now)));

        var result = new DeadLetterQueryController(repository).unresolved();

        assertThat(result.count()).isEqualTo(2);
        assertThat(result.totalNotionalUsd()).isEqualByComparingTo("1250000.00");
        assertThat(result.orders()).extracting(UnresolvedDeadLetter::orderId)
                .containsExactly("order-1", "order-2");
    }

    private static UnresolvedDeadLetter row(String eventId, String orderId, String notionalUsd, Instant now) {
        return new UnresolvedDeadLetter(
                eventId, orderId, "EUR/USD", "SELL", new BigDecimal(notionalUsd),
                "scenario-1", 3, "DEMO_PROCESSING_FAILURE", now, now);
    }
}
