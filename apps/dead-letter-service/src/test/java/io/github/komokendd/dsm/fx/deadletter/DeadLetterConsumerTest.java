package io.github.komokendd.dsm.fx.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.komokendd.dsm.fx.common.KafkaHeaders;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.OrderApprovedEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

class DeadLetterConsumerTest {
    @Test
    void logsOneStructuredEventWhenAnOrderReachesTheDeadLetterTopic() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant submittedAt = Instant.parse("2026-08-24T01:00:00Z");
        Instant failureAt = Instant.parse("2026-08-24T01:00:35Z");
        OrderApprovedEvent order = OrderApprovedEvent.newBuilder()
                .setEventId("event-1")
                .setOrderId("order-1")
                .setEventType("ORDER_APPROVED")
                .setEventVersion(1)
                .setOccurredAt(submittedAt)
                .setCurrencyPair("EUR/USD")
                .setDemoScenarioId("scenario-1")
                .setSide("BUY")
                .setQuantity(new BigDecimal("100000.00"))
                .setLimitPrice(new BigDecimal("1.100000"))
                .setNotionalUsd(new BigDecimal("110000.00"))
                .setSubmittedAt(submittedAt)
                .setApprovedAt(submittedAt.plusSeconds(1))
                .build();
        ConsumerRecord<String, OrderApprovedEvent> record =
                new ConsumerRecord<>(Topics.EXECUTION_DLT, 0, 0L, order.getOrderId(), order);
        record.headers()
                .add(KafkaHeaders.RETRY_COUNT, bytes("3"))
                .add(KafkaHeaders.FAILURE_CATEGORY, bytes("DEMO_EXECUTION_TIMEOUT"))
                .add(KafkaHeaders.FAILURE_TIMESTAMP, bytes(failureAt.toString()))
                .add(KafkaHeaders.ORIGINATING_TOPIC, bytes(Topics.ORDERS_APPROVED));

        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeadLetterConsumer.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new DeadLetterConsumer(jdbc).onDeadLetter(record);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getMDCPropertyMap())
                .containsEntry("order_id", "order-1")
                .containsEntry("event_type", "DEAD_LETTER")
                .containsEntry("currency_pair", "EUR/USD")
                .containsEntry("side", "BUY")
                .containsEntry("notional_usd", "110000.00")
                .containsEntry("failure_category", "DEMO_EXECUTION_TIMEOUT")
                .containsEntry("failure_timestamp", failureAt.toString())
                .containsEntry("retry_count", "3")
                .containsEntry("demo_scenario_id", "scenario-1");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
