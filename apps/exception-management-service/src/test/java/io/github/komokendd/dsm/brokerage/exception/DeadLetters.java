package io.github.komokendd.dsm.brokerage.exception;

import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import java.math.BigDecimal;
import java.time.Instant;

final class DeadLetters {
    static final Instant EXECUTED_AT = Instant.parse("2026-11-20T00:00:00Z");

    private DeadLetters() {
    }

    static NotificationRequest request(String eventId, String notificationId) {
        return NotificationRequest.newBuilder()
                .setEventId(eventId)
                .setEventType("NOTIFICATION_REQUESTED")
                .setEventVersion(1)
                .setOccurredAt(EXECUTED_AT.plusMillis(20))
                .setAccountId("A00001")
                .setOrderId("order-1")
                .setSymbol("D105")
                .setSide("SELL")
                .setDemoScenarioId("sell-off-1")
                .setNotificationId(notificationId)
                .setSourceEventId("exec-event-1")
                .setNotificationType("EXECUTION")
                .setQuantity(300)
                .setPrice(new BigDecimal("9500.0"))
                .setSourceOccurredAt(EXECUTED_AT)
                .setRequestedAt(EXECUTED_AT.plusMillis(20))
                .build();
    }

    static NotificationDeadLetter row(String eventId, String notificationId, Instant receivedAt) {
        return new NotificationDeadLetter(
                eventId, notificationId, "A00001", "order-1", "D105", "SELL", "EXECUTION", 300,
                new BigDecimal("9500.0"), "exec-event-1", EXECUTED_AT, "sell-off-1", 3,
                "PUSH_PROVIDER_UNAVAILABLE", receivedAt.minusMillis(5), Topics.NOTIFICATIONS_RETRY_30S, receivedAt);
    }
}
