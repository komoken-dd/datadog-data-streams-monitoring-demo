package io.github.komokendd.dsm.fx.deadletter;

import io.github.komokendd.dsm.fx.common.EventLogContext;
import io.github.komokendd.dsm.fx.common.KafkaHeaders;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.OrderApprovedEvent;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
final class DeadLetterConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterConsumer.class);
    private final JdbcTemplate jdbc;

    DeadLetterConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @KafkaListener(
            topics = Topics.EXECUTION_DLT,
            groupId = "dead-letter-service-v1",
            containerFactory = "deadLetterKafkaListenerFactory")
    void onDeadLetter(ConsumerRecord<String, OrderApprovedEvent> record) {
        OrderApprovedEvent event = record.value();
        int retryCount = Integer.parseInt(header(record, KafkaHeaders.RETRY_COUNT, "0"));
        String failureCategory = header(record, KafkaHeaders.FAILURE_CATEGORY, "UNKNOWN");
        Instant failureTimestamp = Instant.parse(
                header(record, KafkaHeaders.FAILURE_TIMESTAMP, Instant.now().toString()));
        String originatingTopic = header(record, KafkaHeaders.ORIGINATING_TOPIC, Topics.ORDERS_APPROVED);
        Instant receivedAt = Instant.now();
        jdbc.update("""
                insert into dead_letter_service.dead_letters
                    (event_id, order_id, currency_pair, side, quantity, limit_price, notional_usd,
                     submitted_at, approved_at, demo_scenario_id, retry_count, failure_category,
                     failure_timestamp, originating_topic, received_at, resolved_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null)
                on conflict (event_id) do nothing
                """,
                event.getEventId(), event.getOrderId(), event.getCurrencyPair(), event.getSide(),
                event.getQuantity(), event.getLimitPrice(), event.getNotionalUsd(),
                Timestamp.from(event.getSubmittedAt()), Timestamp.from(event.getApprovedAt()),
                event.getDemoScenarioId(),
                retryCount, failureCategory, Timestamp.from(failureTimestamp), originatingTopic,
                Timestamp.from(receivedAt));
        try (var ignored = EventLogContext.open(
                        event.getOrderId(), event.getEventId(), "DEAD_LETTER",
                        event.getCurrencyPair(), event.getDemoScenarioId())
                .retry(retryCount)
                .attribute("side", event.getSide())
                .attribute("notional_usd", event.getNotionalUsd().toPlainString())
                .attribute("failure_category", failureCategory)
                .attribute("failure_timestamp", failureTimestamp)
                .attribute("originating_topic", originatingTopic)
                .attribute("dlt_received_at", receivedAt)) {
            LOG.warn("Unresolved synthetic execution dead letter persisted");
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name, String fallback) {
        var header = record.headers().lastHeader(name);
        return header == null ? fallback : new String(header.value(), StandardCharsets.UTF_8);
    }
}
