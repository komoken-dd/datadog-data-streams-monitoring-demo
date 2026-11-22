package io.github.komokendd.dsm.brokerage.exception;

import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class NotificationDeadLetterConsumer {
    static final String EVENT_TYPE = "NOTIFICATION_DEAD_LETTER";
    private static final Logger LOG = LoggerFactory.getLogger(NotificationDeadLetterConsumer.class);

    private final NotificationDeadLetterRepository repository;
    private final Clock clock;

    NotificationDeadLetterConsumer(NotificationDeadLetterRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @KafkaListener(
            topics = Topics.NOTIFICATIONS_DLT,
            groupId = "exception-management-service-v1",
            containerFactory = "deadLetterListenerFactory")
    void onDeadLetter(ConsumerRecord<String, NotificationRequest> record) {
        NotificationDeadLetter row = NotificationDeadLetter.from(record, clock.instant());
        boolean inserted = repository.insert(row);
        try (var ignored = EventLogContext.open(
                        row.accountId(), row.orderId(), row.eventId(), EVENT_TYPE, row.symbol(), row.demoScenarioId())
                .retry(row.retryCount())
                .attribute("side", row.side())
                .attribute("notification_id", row.notificationId())
                .attribute("notification_type", row.notificationType())
                .attribute("failure_category", row.failureCategory())
                .attribute("failure_timestamp", row.failureTimestamp())
                .attribute("originating_topic", row.originatingTopic())
                .attribute("dlt_received_at", row.receivedAt())) {
            if (inserted) {
                LOG.warn("Undelivered customer notification recorded as an open exception");
            } else {
                LOG.info("Dead letter already recorded, redelivery ignored");
            }
        }
    }
}
