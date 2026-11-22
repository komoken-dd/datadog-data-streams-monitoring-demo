package io.github.komokendd.dsm.brokerage.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationMessagesTest {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:02Z");

    @Test
    void formatsExecutionWithGroupedWholeYenPrice() {
        var request = NotificationRequests.fromExecution(TestEvents.execution("e-1", 300, "9500"), NOW);

        assertThat(NotificationMessages.format(request)).isEqualTo("D105 SELL 300 @ 9,500 executed");
    }

    @Test
    void keepsTheTenthOfAYenTickAndGroupsLargeQuantities() {
        var request = NotificationRequests.fromExecution(TestEvents.execution("e-2", 12000, "1234.5"), NOW);

        assertThat(NotificationMessages.format(request)).isEqualTo("D105 SELL 12,000 @ 1,234.5 executed");
    }

    @Test
    void formatsExpiry() {
        var request = NotificationRequests.fromExpiry(TestEvents.expiry("x-1", 400), NOW);

        assertThat(NotificationMessages.format(request)).isEqualTo("D101 BUY 400 expired unfilled");
    }
}
