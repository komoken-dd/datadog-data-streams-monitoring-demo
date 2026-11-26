package io.github.komokendd.dsm.brokerage.exception;

import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.KafkaHeaders;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requeues a bounded batch after provider recovery. Rows are resolved when queued,
 * not when delivered. Repeated calls resume unresolved work; notification identity
 * remains stable across acknowledged or ambiguously acknowledged publishes.
 */
@Service
class RedriveService {
    static final String REDRIVEN_EVENT_TYPE = "NOTIFICATION_REQUESTED";
    private static final Logger LOG = LoggerFactory.getLogger(RedriveService.class);

    record RedriveResult(int redrivenCount, long remainingCount, Instant completedAt) {
    }

    private final NotificationDeadLetterRepository repository;
    private final KafkaTemplate<String, Object> kafka;
    private final Clock clock;
    private final Supplier<String> eventIds;
    private final int batchSize;
    private final Duration maxDuration;

    @Autowired
    RedriveService(NotificationDeadLetterRepository repository, KafkaTemplate<String, Object> kafka, Clock clock,
            @Value("${demo.notification-redrive.batch-size:100}") int batchSize,
            @Value("${demo.notification-redrive.max-duration:5s}") Duration maxDuration) {
        this(repository, kafka, clock, () -> UUID.randomUUID().toString(), batchSize, maxDuration);
    }

    RedriveService(
            NotificationDeadLetterRepository repository,
            KafkaTemplate<String, Object> kafka,
            Clock clock,
            Supplier<String> eventIds) {
        this(repository, kafka, clock, eventIds, 100, Duration.ofSeconds(5));
    }

    RedriveService(NotificationDeadLetterRepository repository, KafkaTemplate<String, Object> kafka,
            Clock clock, Supplier<String> eventIds, int batchSize, Duration maxDuration) {
        if (batchSize < 1 || batchSize > 1000 || maxDuration.isNegative() || maxDuration.isZero()
                || maxDuration.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Redrive requires batch-size 1..1000 and max-duration greater than 0 through 10s");
        }
        this.repository = repository;
        this.kafka = kafka;
        this.clock = clock;
        this.eventIds = eventIds;
        this.batchSize = batchSize;
        this.maxDuration = maxDuration;
    }

    @Transactional(rollbackFor = Exception.class, timeout = 15)
    public RedriveResult redrive() throws Exception {
        long started = System.nanoTime();
        List<NotificationDeadLetter> rows = repository.lockUnresolved(batchSize);
        int redriven = 0;
        for (NotificationDeadLetter row : rows) {
            long remainingNanos = maxDuration.toNanos() - (System.nanoTime() - started);
            if (remainingNanos <= 0) {
                break;
            }
            String redriveEventId = eventIds.get();
            NotificationRequest request = toRequest(row, redriveEventId, clock.instant());
            var headers = new RecordHeaders()
                    .add(KafkaHeaders.REDRIVEN, bytes("true"))
                    .add(KafkaHeaders.RETRY_COUNT, bytes("0"))
                    .add(KafkaHeaders.ORIGINATING_TOPIC, bytes(Topics.NOTIFICATIONS_DLT));
            // Resolve only after the broker acknowledged the write; a failure rolls back the batch
            // and the next call resumes the batch using the same notification_id.
            // Kafka writes already acknowledged cannot be rolled back by a DB
            // transaction, so the provider's dedupe window still matters.
            try {
                kafka.send(new ProducerRecord<>(Topics.NOTIFICATIONS_RETRY_5S, null, row.accountId(), request, headers))
                        .get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            repository.markResolved(row.eventId(), redriveEventId);
            redriven++;
            try (var ignored = EventLogContext.open(
                            row.accountId(), row.orderId(), redriveEventId, "NOTIFICATION_REDRIVEN",
                            row.symbol(), row.demoScenarioId())
                    .attribute("notification_id", row.notificationId())
                    .attribute("dead_letter_event_id", row.eventId())) {
                LOG.info("Dead-lettered customer notification redriven");
            }
        }
        Instant completedAt = clock.instant();
        long remaining = repository.unresolvedSummary().count();
        LOG.info("Notification redrive batch queued, redriven_count={}, remaining_count={}", redriven, remaining);
        return new RedriveResult(redriven, remaining, completedAt);
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
