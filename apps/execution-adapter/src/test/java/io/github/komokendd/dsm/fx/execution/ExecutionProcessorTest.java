package io.github.komokendd.dsm.fx.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.fx.common.MetricNames;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.OrderApprovedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class ExecutionProcessorTest {
    @Test
    void routesOnlyTheNamedProcessingFailureScenarioBeforeRedrive() {
        assertThat(ExecutionProcessor.shouldRouteToFailure("processing-failure-123", false)).isTrue();
        assertThat(ExecutionProcessor.shouldRouteToFailure("other-scenario-123", false)).isFalse();
        assertThat(ExecutionProcessor.shouldRouteToFailure("processing-failure-123", true)).isFalse();
        assertThat(ExecutionProcessor.shouldRouteToFailure(null, false)).isFalse();
    }

    @Test
    void recordsRealizedSyntheticFeeWhenOrderExecutes() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutionProcessor processor = new ExecutionProcessor(kafka, registry, new ExecutionControl(0, 0));
        Instant submittedAt = Instant.now().minusSeconds(1);
        OrderApprovedEvent order = OrderApprovedEvent.newBuilder()
                .setEventId("event-1")
                .setOrderId("order-1")
                .setEventType("ORDER_APPROVED")
                .setEventVersion(1)
                .setOccurredAt(submittedAt)
                .setCurrencyPair("EUR/USD")
                .setDemoScenarioId(null)
                .setSide("BUY")
                .setQuantity(new BigDecimal("1000000.00"))
                .setLimitPrice(new BigDecimal("1.100000"))
                .setNotionalUsd(new BigDecimal("1000000.00"))
                .setSubmittedAt(submittedAt)
                .setApprovedAt(submittedAt)
                .build();

        processor.onApproved(new ConsumerRecord<>(Topics.ORDERS_APPROVED, 0, 0L, order.getOrderId(), order));

        var fee = registry.get(MetricNames.EXECUTIONS_SYNTHETIC_FEE)
                .tag("currency_pair", "EUR/USD")
                .tag("side", "BUY")
                .summary();
        assertThat(fee.count()).isEqualTo(1);
        assertThat(fee.totalAmount()).isEqualTo(30.0);
    }
}
