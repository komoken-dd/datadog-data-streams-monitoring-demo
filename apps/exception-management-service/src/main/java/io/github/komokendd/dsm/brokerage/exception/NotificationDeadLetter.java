package io.github.komokendd.dsm.brokerage.exception;

import io.github.komokendd.dsm.brokerage.common.KafkaHeaders;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/** A customer notification that exhausted its automatic retries. */
record NotificationDeadLetter(
        String eventId,
        String notificationId,
        String accountId,
        String orderId,
        String symbol,
        String side,
        String notificationType,
        long quantity,
        BigDecimal price,
        String sourceEventId,
        Instant sourceOccurredAt,
        String demoScenarioId,
        int retryCount,
        String failureCategory,
        Instant failureTimestamp,
        String originatingTopic,
        Instant receivedAt) {

    static NotificationDeadLetter from(ConsumerRecord<String, NotificationRequest> record, Instant receivedAt) {
        NotificationRequest request = record.value();
        return new NotificationDeadLetter(
                request.getEventId(),
                request.getNotificationId(),
                request.getAccountId(),
                request.getOrderId(),
                request.getSymbol(),
                request.getSide(),
                request.getNotificationType(),
                request.getQuantity(),
                request.getPrice(),
                request.getSourceEventId(),
                request.getSourceOccurredAt(),
                request.getDemoScenarioId(),
                retryCount(header(record, KafkaHeaders.RETRY_COUNT)),
                orDefault(header(record, KafkaHeaders.FAILURE_CATEGORY), "UNKNOWN"),
                instant(header(record, KafkaHeaders.FAILURE_TIMESTAMP), receivedAt),
                orDefault(header(record, KafkaHeaders.ORIGINATING_TOPIC), Topics.NOTIFICATIONS_DLT),
                receivedAt);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8).trim();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static int retryCount(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static Instant instant(String value, Instant fallback) {
        try {
            return value == null ? fallback : Instant.parse(value);
        } catch (DateTimeParseException malformed) {
            return fallback;
        }
    }
}
