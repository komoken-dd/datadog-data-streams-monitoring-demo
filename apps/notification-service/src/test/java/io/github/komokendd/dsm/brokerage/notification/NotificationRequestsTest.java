package io.github.komokendd.dsm.brokerage.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.common.EventIds;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationRequestsTest {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:02Z");

    @Test
    void buildsExecutionNotificationWithDeterministicIds() {
        var execution = TestEvents.execution("exec-event-1", 300, "9500");

        var request = NotificationRequests.fromExecution(execution, NOW);
        var again = NotificationRequests.fromExecution(execution, NOW.plusSeconds(10));

        assertThat(request.getNotificationId())
                .isEqualTo(EventIds.derived("exec-event-1", "NOTIFICATION"))
                .isEqualTo(again.getNotificationId());
        assertThat(request.getEventId()).isEqualTo(again.getEventId()).isNotEqualTo(request.getNotificationId());
        assertThat(request.getNotificationType()).isEqualTo("EXECUTION");
        assertThat(request.getQuantity()).isEqualTo(300);
        assertThat(request.getPrice()).isEqualByComparingTo("9500");
        assertThat(request.getPrice().scale()).isEqualTo(1);
        assertThat(request.getSourceEventId()).isEqualTo("exec-event-1");
        assertThat(request.getSourceOccurredAt()).isEqualTo(TestEvents.EXECUTED_AT);
        assertThat(request.getRequestedAt()).isEqualTo(NOW);
        assertThat(request.getAccountId()).isEqualTo("A0001");
        assertThat(request.getDemoScenarioId()).isEqualTo("sell-off-1");
    }

    @Test
    void buildsExpiryNotificationForTheExpiredQuantityWithoutPrice() {
        var request = NotificationRequests.fromExpiry(TestEvents.expiry("expiry-event-1", 400), NOW);

        assertThat(request.getNotificationId()).isEqualTo(EventIds.derived("expiry-event-1", "NOTIFICATION"));
        assertThat(request.getNotificationType()).isEqualTo("EXPIRY");
        assertThat(request.getQuantity()).isEqualTo(400);
        assertThat(request.getPrice()).isNull();
        assertThat(request.getSourceOccurredAt()).isEqualTo(TestEvents.EXECUTED_AT);
    }
}
