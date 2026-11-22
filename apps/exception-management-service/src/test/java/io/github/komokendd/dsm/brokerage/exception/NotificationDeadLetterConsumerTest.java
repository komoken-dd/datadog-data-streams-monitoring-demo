package io.github.komokendd.dsm.brokerage.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.komokendd.dsm.brokerage.common.KafkaHeaders;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class NotificationDeadLetterConsumerTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-11-20T00:00:40Z");
    private static final Instant FAILED_AT = Instant.parse("2026-11-20T00:00:39.5Z");

    @Test
    void mapsTheRequestAndRetryHeadersToADeadLetterRow() {
        var row = NotificationDeadLetter.from(dltRecord(), RECEIVED_AT);

        assertThat(row.eventId()).isEqualTo("request-1");
        assertThat(row.notificationId()).isEqualTo("notification-1");
        assertThat(row.accountId()).isEqualTo("A00001");
        assertThat(row.orderId()).isEqualTo("order-1");
        assertThat(row.symbol()).isEqualTo("D105");
        assertThat(row.side()).isEqualTo("SELL");
        assertThat(row.notificationType()).isEqualTo("EXECUTION");
        assertThat(row.quantity()).isEqualTo(300);
        assertThat(row.price()).isEqualByComparingTo("9500");
        assertThat(row.sourceEventId()).isEqualTo("exec-event-1");
        assertThat(row.sourceOccurredAt()).isEqualTo(DeadLetters.EXECUTED_AT);
        assertThat(row.demoScenarioId()).isEqualTo("sell-off-1");
        assertThat(row.retryCount()).isEqualTo(3);
        assertThat(row.failureCategory()).isEqualTo("PUSH_PROVIDER_TIMEOUT");
        assertThat(row.failureTimestamp()).isEqualTo(FAILED_AT);
        assertThat(row.originatingTopic()).isEqualTo(Topics.NOTIFICATIONS_RETRY_30S);
        assertThat(row.receivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    void toleratesMissingHeaders() {
        var record = new ConsumerRecord<>(Topics.NOTIFICATIONS_DLT, 0, 0L, "A00001",
                DeadLetters.request("request-1", "notification-1"));

        var row = NotificationDeadLetter.from(record, RECEIVED_AT);

        assertThat(row.retryCount()).isZero();
        assertThat(row.failureCategory()).isEqualTo("UNKNOWN");
        assertThat(row.failureTimestamp()).isEqualTo(RECEIVED_AT);
        assertThat(row.originatingTopic()).isEqualTo(Topics.NOTIFICATIONS_DLT);
    }

    @Test
    void persistsAndLogsOneStructuredEventPerDeadLetter() {
        var repository = mock(NotificationDeadLetterRepository.class);
        when(repository.insert(any())).thenReturn(true);
        var consumer = new NotificationDeadLetterConsumer(repository, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(NotificationDeadLetterConsumer.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            consumer.onDeadLetter(dltRecord());
        } finally {
            logger.detachAppender(appender);
        }

        var captor = ArgumentCaptor.forClass(NotificationDeadLetter.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo("request-1");
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMDCPropertyMap())
                    .containsEntry("account_id", "A00001")
                    .containsEntry("order_id", "order-1")
                    .containsEntry("event_id", "request-1")
                    .containsEntry("event_type", "NOTIFICATION_DEAD_LETTER")
                    .containsEntry("symbol", "D105")
                    .containsEntry("notification_id", "notification-1")
                    .containsEntry("failure_category", "PUSH_PROVIDER_TIMEOUT")
                    .containsEntry("failure_timestamp", FAILED_AT.toString())
                    .containsEntry("originating_topic", Topics.NOTIFICATIONS_RETRY_30S)
                    .containsEntry("retry_count", "3")
                    .containsEntry("demo_scenario_id", "sell-off-1");
        });
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void redeliveredDeadLetterIsNotReportedAsANewException() {
        var repository = mock(NotificationDeadLetterRepository.class);
        when(repository.insert(any())).thenReturn(false);
        var consumer = new NotificationDeadLetterConsumer(repository, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(NotificationDeadLetterConsumer.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            consumer.onDeadLetter(dltRecord());
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
    }

    private static ConsumerRecord<String, NotificationRequest> dltRecord() {
        var record = new ConsumerRecord<>(Topics.NOTIFICATIONS_DLT, 0, 0L, "A00001",
                DeadLetters.request("request-1", "notification-1"));
        record.headers()
                .add(KafkaHeaders.RETRY_COUNT, bytes("3"))
                .add(KafkaHeaders.FAILURE_CATEGORY, bytes("PUSH_PROVIDER_TIMEOUT"))
                .add(KafkaHeaders.FAILURE_TIMESTAMP, bytes(FAILED_AT.toString()))
                .add(KafkaHeaders.ORIGINATING_TOPIC, bytes(Topics.NOTIFICATIONS_RETRY_30S));
        return record;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
