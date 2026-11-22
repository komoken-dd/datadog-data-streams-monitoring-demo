package io.github.komokendd.dsm.brokerage.notification;

import io.github.komokendd.dsm.brokerage.common.EventIds;
import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.KafkaHeaders;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.NotificationDeliveredEvent;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Delivers customer notifications. Each listener makes exactly one push attempt per record; a
 * failed attempt is handed to a delayed retry topic instead of blocking the source partition.
 * Re-sending is safe because the push provider deduplicates on {@code notification_id}.
 */
@Component
class NotificationProcessor {
    static final String DELIVERED_EVENT_TYPE = "NOTIFICATION_DELIVERED";
    static final Duration FIVE_SECONDS = Duration.ofSeconds(5);
    static final Duration THIRTY_SECONDS = Duration.ofSeconds(30);
    private static final Logger LOG = LoggerFactory.getLogger(NotificationProcessor.class);

    private final KafkaTemplate<String, Object> kafka;
    private final PushGateway pushGateway;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final RetryWait retryWait;
    private final FailureRouter failureRouter = new FailureRouter();

    NotificationProcessor(
            KafkaTemplate<String, Object> kafka,
            PushGateway pushGateway,
            MeterRegistry metrics,
            Clock clock,
            RetryWait retryWait) {
        this.kafka = kafka;
        this.pushGateway = pushGateway;
        this.metrics = metrics;
        this.clock = clock;
        this.retryWait = retryWait;
    }

    @KafkaListener(
            topics = Topics.EXECUTIONS,
            groupId = "notification-service-v1",
            containerFactory = "executionListenerFactory")
    void onExecution(ConsumerRecord<String, ExecutionReportEvent> record) {
        NotificationRequest request = NotificationRequests.fromExecution(record.value(), clock.instant());
        attempt(request, record.topic(), record.headers(), 0);
    }

    @KafkaListener(
            topics = Topics.ORDERS_EXPIRED,
            groupId = "notification-service-v1",
            containerFactory = "expiryListenerFactory")
    void onExpiry(ConsumerRecord<String, OrderExpiredEvent> record) {
        NotificationRequest request = NotificationRequests.fromExpiry(record.value(), clock.instant());
        attempt(request, record.topic(), record.headers(), 0);
    }

    @KafkaListener(
            topics = Topics.NOTIFICATIONS_RETRY_5S,
            groupId = "notification-service-retry-5s-v1",
            containerFactory = "notificationRequestListenerFactory")
    void onFiveSecondRetry(ConsumerRecord<String, NotificationRequest> record) throws InterruptedException {
        retryWait.awaitDue(record.timestamp(), FIVE_SECONDS);
        attempt(record.value(), record.topic(), record.headers(), headerInt(record.headers(), 1));
    }

    @KafkaListener(
            topics = Topics.NOTIFICATIONS_RETRY_30S,
            groupId = "notification-service-retry-30s-v1",
            containerFactory = "notificationRequestListenerFactory")
    void onThirtySecondRetry(ConsumerRecord<String, NotificationRequest> record) throws InterruptedException {
        retryWait.awaitDue(record.timestamp(), THIRTY_SECONDS);
        attempt(record.value(), record.topic(), record.headers(), headerInt(record.headers(), 2));
    }

    void attempt(NotificationRequest request, String sourceTopic, Headers sourceHeaders, int completedRetries) {
        boolean redriven = headerBoolean(sourceHeaders, KafkaHeaders.REDRIVEN);
        try (var context = EventLogContext.open(
                        request.getAccountId(),
                        request.getOrderId(),
                        request.getEventId(),
                        request.getEventType(),
                        request.getSymbol(),
                        request.getDemoScenarioId())
                .retry(completedRetries)
                .attribute("notification_id", request.getNotificationId())
                .attribute("notification_type", request.getNotificationType())
                .attribute("redriven", redriven)) {
            PushGateway.PushResponse response;
            try {
                response = pushGateway.push(new PushGateway.PushMessage(
                        request.getNotificationId(),
                        request.getAccountId(),
                        request.getOrderId(),
                        request.getNotificationType(),
                        NotificationMessages.format(request)));
            } catch (PushDeliveryException failure) {
                routeFailure(context, request, sourceTopic, sourceHeaders, completedRetries, failure);
                return;
            }
            publishDelivered(request, completedRetries + 1, redriven, response.duplicate());
        }
    }

    private void publishDelivered(NotificationRequest request, int attempts, boolean redriven, boolean duplicate) {
        Instant deliveredAt = clock.instant();
        var delivered = NotificationDeliveredEvent.newBuilder()
                .setEventId(EventIds.derived(request.getNotificationId(), DELIVERED_EVENT_TYPE))
                .setEventType(DELIVERED_EVENT_TYPE)
                .setEventVersion(1)
                .setOccurredAt(deliveredAt)
                .setAccountId(request.getAccountId())
                .setOrderId(request.getOrderId())
                .setSymbol(request.getSymbol())
                .setSide(request.getSide())
                .setDemoScenarioId(request.getDemoScenarioId())
                .setNotificationId(request.getNotificationId())
                .setSourceEventId(request.getSourceEventId())
                .setNotificationType(request.getNotificationType())
                .setAttempts(attempts)
                .setRedriven(redriven)
                .setSourceOccurredAt(request.getSourceOccurredAt())
                .setDeliveredAt(deliveredAt)
                .build();
        kafka.send(Topics.NOTIFICATIONS_DELIVERED, request.getAccountId(), delivered).join();
        Counter.builder(MetricNames.NOTIFICATIONS_DELIVERED)
                .tag("notification_type", request.getNotificationType())
                .tag("redriven", Boolean.toString(redriven))
                .register(metrics)
                .increment();
        DistributionSummary.builder(MetricNames.NOTIFICATION_DELIVERY_DELAY)
                .baseUnit("milliseconds")
                .publishPercentileHistogram()
                .tag("notification_type", request.getNotificationType())
                .tag("redriven", Boolean.toString(redriven))
                .register(metrics)
                .record(Math.max(0, Duration.between(request.getSourceOccurredAt(), deliveredAt).toMillis()));
        LOG.info("Customer notification delivered, attempts={}, provider_duplicate={}", attempts, duplicate);
    }

    private void routeFailure(
            EventLogContext context,
            NotificationRequest request,
            String sourceTopic,
            Headers sourceHeaders,
            int completedRetries,
            PushDeliveryException failure) {
        FailureRoute route = failureRouter.route(completedRetries);
        String category = failure.failureCategory();
        Headers headers = KafkaHeaders.copyDemoMetadata(sourceHeaders);
        replaceHeader(headers, KafkaHeaders.RETRY_COUNT, Integer.toString(completedRetries + 1));
        replaceHeader(headers, KafkaHeaders.FAILURE_CATEGORY, category);
        replaceHeader(headers, KafkaHeaders.FAILURE_TIMESTAMP, clock.instant().toString());
        replaceHeader(headers, KafkaHeaders.ORIGINATING_TOPIC, sourceTopic);
        kafka.send(new ProducerRecord<>(route.topic(), null, request.getAccountId(), request, headers)).join();
        String meter = route == FailureRoute.DEAD_LETTER
                ? MetricNames.NOTIFICATION_DLT_EVENTS
                : MetricNames.NOTIFICATION_RETRY_ATTEMPTS;
        Counter.builder(meter)
                .tag("notification_type", request.getNotificationType())
                .tag("failure_category", category)
                .register(metrics)
                .increment();
        context.attribute("failure_category", category).attribute("destination_topic", route.topic());
        if (route == FailureRoute.DEAD_LETTER) {
            LOG.error("Customer notification moved to the dead-letter topic after {} attempts", completedRetries + 1);
        } else {
            LOG.warn("Push provider failed, notification scheduled for retry: {}", failure.getMessage());
        }
    }

    private static void replaceHeader(Headers headers, String name, String value) {
        headers.remove(name);
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    static int headerInt(Headers headers, int fallback) {
        var header = headers.lastHeader(KafkaHeaders.RETRY_COUNT);
        if (header == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    static boolean headerBoolean(Headers headers, String name) {
        var header = headers.lastHeader(name);
        return header != null && Boolean.parseBoolean(new String(header.value(), StandardCharsets.UTF_8).trim());
    }
}
