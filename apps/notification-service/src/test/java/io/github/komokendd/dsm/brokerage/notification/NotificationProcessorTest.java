package io.github.komokendd.dsm.brokerage.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.brokerage.common.EventIds;
import io.github.komokendd.dsm.brokerage.common.KafkaHeaders;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.NotificationDeliveredEvent;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class NotificationProcessorTest {
    private static final Instant NOW = TestEvents.EXECUTED_AT.plusMillis(1500);

    private KafkaTemplate<String, Object> kafka;
    private SimpleMeterRegistry registry;
    private ScriptedPushGateway gateway;
    private List<Duration> sleeps;
    private NotificationProcessor processor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        registry = new SimpleMeterRegistry();
        gateway = new ScriptedPushGateway();
        sleeps = new ArrayList<>();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        processor = new NotificationProcessor(kafka, gateway, registry, clock, new RetryWait(clock, sleeps::add));
    }

    @Test
    void deliversExecutionNotificationAndPublishesDeliveredEvent() {
        ExecutionReportEvent execution = TestEvents.execution("exec-event-1", 300, "9500");

        processor.onExecution(new ConsumerRecord<>(Topics.EXECUTIONS, 0, 0L, "A0001", execution));

        assertThat(gateway.sent).singleElement().satisfies(message -> {
            assertThat(message.notificationId()).isEqualTo(EventIds.derived("exec-event-1", "NOTIFICATION"));
            assertThat(message.message()).isEqualTo("D105 SELL 300 @ 9,500 executed");
            assertThat(message.accountId()).isEqualTo("A0001");
        });
        var delivered = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(Topics.NOTIFICATIONS_DELIVERED), eq("A0001"), delivered.capture());
        NotificationDeliveredEvent event = (NotificationDeliveredEvent) delivered.getValue();
        String notificationId = EventIds.derived("exec-event-1", "NOTIFICATION");
        assertThat(event.getNotificationId()).isEqualTo(notificationId);
        assertThat(event.getEventId()).isEqualTo(EventIds.derived(notificationId, "NOTIFICATION_DELIVERED"));
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getRedriven()).isFalse();
        assertThat(event.getSourceEventId()).isEqualTo("exec-event-1");
        assertThat(event.getDeliveredAt()).isEqualTo(NOW);
        verify(kafka, never()).send(any(ProducerRecord.class));

        assertThat(registry.get(MetricNames.NOTIFICATIONS_DELIVERED)
                .tag("notification_type", "EXECUTION").tag("redriven", "false").counter().count())
                .isEqualTo(1.0);
        var delay = registry.get(MetricNames.NOTIFICATION_DELIVERY_DELAY).tag("notification_type", "EXECUTION")
                .summary();
        assertThat(delay.count()).isEqualTo(1);
        assertThat(delay.totalAmount()).isEqualTo(1500.0);
    }

    @Test
    void deliversExpiryNotification() {
        OrderExpiredEvent expiry = TestEvents.expiry("expiry-event-1", 400);

        processor.onExpiry(new ConsumerRecord<>(Topics.ORDERS_EXPIRED, 0, 0L, "A0002", expiry));

        assertThat(gateway.sent).singleElement().satisfies(message -> {
            assertThat(message.notificationType()).isEqualTo("EXPIRY");
            assertThat(message.message()).isEqualTo("D101 BUY 400 expired unfilled");
        });
        assertThat(registry.get(MetricNames.NOTIFICATIONS_DELIVERED)
                .tag("notification_type", "EXPIRY").counter().count()).isEqualTo(1.0);
    }

    @Test
    void firstFailureGoesToTheFiveSecondRetryTopicWithRetryHeaders() {
        gateway.fail(PushDeliveryException.UNAVAILABLE);
        ExecutionReportEvent execution = TestEvents.execution("exec-event-1", 300, "9500");

        processor.onExecution(new ConsumerRecord<>(Topics.EXECUTIONS, 0, 0L, "A0001", execution));

        ProducerRecord<String, Object> routed = routedRecord();
        assertThat(routed.topic()).isEqualTo(Topics.NOTIFICATIONS_RETRY_5S);
        assertThat(routed.key()).isEqualTo("A0001");
        assertThat(((NotificationRequest) routed.value()).getNotificationId())
                .isEqualTo(EventIds.derived("exec-event-1", "NOTIFICATION"));
        assertThat(header(routed.headers(), KafkaHeaders.RETRY_COUNT)).isEqualTo("1");
        assertThat(header(routed.headers(), KafkaHeaders.FAILURE_CATEGORY)).isEqualTo("PUSH_PROVIDER_UNAVAILABLE");
        assertThat(header(routed.headers(), KafkaHeaders.FAILURE_TIMESTAMP)).isEqualTo(NOW.toString());
        assertThat(header(routed.headers(), KafkaHeaders.ORIGINATING_TOPIC)).isEqualTo(Topics.EXECUTIONS);
        assertThat(routed.headers().lastHeader(KafkaHeaders.REDRIVEN)).isNull();
        verify(kafka, never()).send(eq(Topics.NOTIFICATIONS_DELIVERED), anyString(), any());
        assertThat(registry.get(MetricNames.NOTIFICATION_RETRY_ATTEMPTS)
                .tag("failure_category", "PUSH_PROVIDER_UNAVAILABLE").counter().count()).isEqualTo(1.0);
        assertThat(registry.find(MetricNames.NOTIFICATION_DLT_EVENTS).counter()).isNull();
    }

    @Test
    void fiveSecondRetryFailureMovesToThirtySecondsAndKeepsTheRedrivenMarker() throws Exception {
        gateway.fail(PushDeliveryException.TIMEOUT);
        var record = retryRecord(Topics.NOTIFICATIONS_RETRY_5S, NOW.minusSeconds(2), "1", true);

        processor.onFiveSecondRetry(record);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
        ProducerRecord<String, Object> routed = routedRecord();
        assertThat(routed.topic()).isEqualTo(Topics.NOTIFICATIONS_RETRY_30S);
        assertThat(header(routed.headers(), KafkaHeaders.RETRY_COUNT)).isEqualTo("2");
        assertThat(header(routed.headers(), KafkaHeaders.FAILURE_CATEGORY)).isEqualTo("PUSH_PROVIDER_TIMEOUT");
        assertThat(header(routed.headers(), KafkaHeaders.ORIGINATING_TOPIC)).isEqualTo(Topics.NOTIFICATIONS_RETRY_5S);
        assertThat(header(routed.headers(), KafkaHeaders.REDRIVEN)).isEqualTo("true");
        assertThat(routed.headers().headers(KafkaHeaders.RETRY_COUNT)).hasSize(1);
    }

    @Test
    void thirtySecondRetryFailureMovesToTheDeadLetterTopic() throws Exception {
        gateway.fail(PushDeliveryException.UNAVAILABLE);
        var record = retryRecord(Topics.NOTIFICATIONS_RETRY_30S, NOW.minusSeconds(31), "2", false);

        processor.onThirtySecondRetry(record);

        assertThat(sleeps).isEmpty();
        ProducerRecord<String, Object> routed = routedRecord();
        assertThat(routed.topic()).isEqualTo(Topics.NOTIFICATIONS_DLT);
        assertThat(header(routed.headers(), KafkaHeaders.RETRY_COUNT)).isEqualTo("3");
        assertThat(header(routed.headers(), KafkaHeaders.ORIGINATING_TOPIC)).isEqualTo(Topics.NOTIFICATIONS_RETRY_30S);
        assertThat(registry.get(MetricNames.NOTIFICATION_DLT_EVENTS)
                .tag("failure_category", "PUSH_PROVIDER_UNAVAILABLE").counter().count()).isEqualTo(1.0);
        assertThat(registry.find(MetricNames.NOTIFICATION_RETRY_ATTEMPTS).counter()).isNull();
    }

    @Test
    void redrivenRequestIsDeliveredWithTheRedrivenFlag() throws Exception {
        var record = retryRecord(Topics.NOTIFICATIONS_RETRY_5S, NOW.minusSeconds(10), "0", true);

        processor.onFiveSecondRetry(record);

        var delivered = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(Topics.NOTIFICATIONS_DELIVERED), eq("A0001"), delivered.capture());
        NotificationDeliveredEvent event = (NotificationDeliveredEvent) delivered.getValue();
        assertThat(event.getRedriven()).isTrue();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(registry.get(MetricNames.NOTIFICATIONS_DELIVERED).tag("redriven", "true").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void retryWithoutRetryHeaderFallsBackToTheTopicPosition() throws Exception {
        gateway.fail(PushDeliveryException.UNAVAILABLE);
        var record = retryRecord(Topics.NOTIFICATIONS_RETRY_30S, NOW.minusSeconds(31), null, false);

        processor.onThirtySecondRetry(record);

        assertThat(routedRecord().topic()).isEqualTo(Topics.NOTIFICATIONS_DLT);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, Object> routedRecord() {
        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        return captor.getValue();
    }

    private static ConsumerRecord<String, NotificationRequest> retryRecord(
            String topic, Instant enqueuedAt, String retryCount, boolean redriven) {
        NotificationRequest request = NotificationRequests.fromExecution(
                TestEvents.execution("exec-event-1", 300, "9500"), TestEvents.EXECUTED_AT);
        var record = new ConsumerRecord<>(topic, 0, 0L, enqueuedAt.toEpochMilli(),
                TimestampType.CREATE_TIME, 0, 0, "A0001", request,
                new RecordHeaders(), Optional.empty());
        if (retryCount != null) {
            record.headers().add(KafkaHeaders.RETRY_COUNT, bytes(retryCount));
            record.headers().add(KafkaHeaders.FAILURE_CATEGORY, bytes("PUSH_PROVIDER_UNAVAILABLE"));
        }
        if (redriven) {
            record.headers().add(KafkaHeaders.REDRIVEN, bytes("true"));
        }
        return record;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String header(Headers headers, String name) {
        var header = headers.lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static final class ScriptedPushGateway implements PushGateway {
        private final List<PushMessage> sent = new ArrayList<>();
        private final Deque<String> failures = new ArrayDeque<>();

        void fail(String category) {
            failures.add(category);
        }

        @Override
        public PushResponse push(PushMessage message) {
            sent.add(message);
            String failure = failures.poll();
            if (failure != null) {
                throw new PushDeliveryException(failure, "scripted failure", null);
            }
            return new PushResponse(message.notificationId(), false);
        }
    }
}
