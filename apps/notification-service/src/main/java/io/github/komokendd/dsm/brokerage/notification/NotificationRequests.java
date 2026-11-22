package io.github.komokendd.dsm.brokerage.notification;

import io.github.komokendd.dsm.brokerage.common.EventIds;
import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import java.time.Instant;

/** Builds customer notifications from order events with ids derived from the source event. */
final class NotificationRequests {
    static final String EVENT_TYPE = "NOTIFICATION_REQUESTED";
    static final String EXECUTION = "EXECUTION";
    static final String EXPIRY = "EXPIRY";

    private NotificationRequests() {
    }

    static String notificationId(String sourceEventId) {
        return EventIds.derived(sourceEventId, "NOTIFICATION");
    }

    static NotificationRequest fromExecution(ExecutionReportEvent execution, Instant requestedAt) {
        return NotificationRequest.newBuilder()
                .setEventId(EventIds.derived(execution.getEventId(), EVENT_TYPE))
                .setEventType(EVENT_TYPE)
                .setEventVersion(1)
                .setOccurredAt(requestedAt)
                .setAccountId(execution.getAccountId())
                .setOrderId(execution.getOrderId())
                .setSymbol(execution.getSymbol())
                .setSide(execution.getSide())
                .setDemoScenarioId(execution.getDemoScenarioId())
                .setNotificationId(notificationId(execution.getEventId()))
                .setSourceEventId(execution.getEventId())
                .setNotificationType(EXECUTION)
                .setQuantity(execution.getExecutedQuantity())
                .setPrice(Jpy.price(execution.getExecutionPrice()))
                .setSourceOccurredAt(execution.getExecutedAt())
                .setRequestedAt(requestedAt)
                .build();
    }

    static NotificationRequest fromExpiry(OrderExpiredEvent expiry, Instant requestedAt) {
        return NotificationRequest.newBuilder()
                .setEventId(EventIds.derived(expiry.getEventId(), EVENT_TYPE))
                .setEventType(EVENT_TYPE)
                .setEventVersion(1)
                .setOccurredAt(requestedAt)
                .setAccountId(expiry.getAccountId())
                .setOrderId(expiry.getOrderId())
                .setSymbol(expiry.getSymbol())
                .setSide(expiry.getSide())
                .setDemoScenarioId(expiry.getDemoScenarioId())
                .setNotificationId(notificationId(expiry.getEventId()))
                .setSourceEventId(expiry.getEventId())
                .setNotificationType(EXPIRY)
                .setQuantity(expiry.getExpiredQuantity())
                .setPrice(null)
                .setSourceOccurredAt(expiry.getExpiredAt())
                .setRequestedAt(requestedAt)
                .build();
    }
}
