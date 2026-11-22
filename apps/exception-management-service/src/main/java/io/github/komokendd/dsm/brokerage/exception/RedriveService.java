package io.github.komokendd.dsm.brokerage.exception;

import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.KafkaHeaders;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-sends every unresolved notification once the provider is healthy again. Each request keeps
 * its notification_id, so a notification the provider already accepted is not shown twice.
 */
@Service
class RedriveService {
    static final String REDRIVEN_EVENT_TYPE = "NOTIFICATION_REQUESTED";
    private static final long ACK_TIMEOUT_SECONDS = 10;
    private static final Logger LOG = LoggerFactory.getLogger(RedriveService.class);

    record RedriveResult(int redrivenCount, Instant completedAt) {
    }

    private final NotificationDeadLetterRepository repository;
    private final KafkaTemplate<String, Object> kafka;
    private final Clock clock;
    private final Supplier<String> eventIds;

    @Autowired
    RedriveService(NotificationDeadLetterRepository repository, KafkaTemplate<String, Object> kafka, Clock clock) {
        this(repository, kafka, clock, () -> UUID.randomUUID().toString());
    }

    RedriveService(
            NotificationDeadLetterRepository repository,
            KafkaTemplate<String, Object> kafka,
            Clock clock,
            Supplier<String> eventIds) {
        this.repository = repository;
        this.kafka = kafka;
        this.clock = clock;
        this.eventIds = eventIds;
    }

    @Transactional
    public RedriveResult redrive() throws Exception {
        List<NotificationDeadLetter> rows = repository.lockUnresolved();
        for (NotificationDeadLetter row : rows) {
            String redriveEventId = eventIds.get();
            NotificationRequest request = toRequest(row, redriveEventId, clock.instant());
            var headers = new RecordHeaders()
                    .add(KafkaHeaders.REDRIVEN, bytes("true"))
                    .add(KafkaHeaders.RETRY_COUNT, bytes("0"))
                    .add(KafkaHeaders.ORIGINATING_TOPIC, bytes(Topics.NOTIFICATIONS_DLT));
            // Resolve only after the broker acknowledged the write; a failure rolls back the batch
            // and the next redrive re-sends, which the provider deduplicates by notification_id.
            kafka.send(new ProducerRecord<>(Topics.NOTIFICATIONS_RETRY_5S, null, row.accountId(), request, headers))
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            repository.markResolved(row.eventId(), redriveEventId);
            try (var ignored = EventLogContext.open(
                            row.accountId(), row.orderId(), redriveEventId, "NOTIFICATION_REDRIVEN",
                            row.symbol(), row.demoScenarioId())
                    .attribute("notification_id", row.notificationId())
                    .attribute("dead_letter_event_id", row.eventId())) {
                LOG.info("Dead-lettered customer notification redriven");
            }
        }
        Instant completedAt = clock.instant();
        LOG.info("Notification redrive completed, redriven_count={}", rows.size());
        return new RedriveResult(rows.size(), completedAt);
    }

    static NotificationRequest toRequest(NotificationDeadLetter row, String eventId, Instant now) {
        return NotificationRequest.newBuilder()
                .setEventId(eventId)
                .setEventType(REDRIVEN_EVENT_TYPE)
                .setEventVersion(1)
                .setOccurredAt(now)
                .setAccountId(row.accountId())
                .setOrderId(row.orderId())
                .setSymbol(row.symbol())
                .setSide(row.side())
                .setDemoScenarioId(row.demoScenarioId())
                .setNotificationId(row.notificationId())
                .setSourceEventId(row.sourceEventId())
                .setNotificationType(row.notificationType())
                .setQuantity(row.quantity())
                .setPrice(row.price())
                .setSourceOccurredAt(row.sourceOccurredAt())
                .setRequestedAt(now)
                .build();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
