package io.github.komokendd.dsm.fx.pretrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.fx.common.MetricNames;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.OrderApprovedEvent;
import io.github.komokendd.dsm.fx.schema.OrderReceivedEvent;
import io.github.komokendd.dsm.fx.schema.OrderRejectedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class PreTradeControlTest {
    private static final BigDecimal MAXIMUM_NOTIONAL_USD = new BigDecimal("5000000.00");

    private KafkaTemplate<String, Object> kafka;
    private SimpleMeterRegistry registry;
    private PreTradeControl control;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        registry = new SimpleMeterRegistry();
        control = new PreTradeControl(kafka, registry, MAXIMUM_NOTIONAL_USD);
    }

    @Test
    void approvesOrderAtTheMaximumNotional() {
        control.onOrder(order(MAXIMUM_NOTIONAL_USD));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(Topics.ORDERS_APPROVED), eq("order-1"), event.capture());
        verify(kafka, never()).send(eq(Topics.ORDERS_REJECTED), anyString(), any());
        OrderApprovedEvent approved = (OrderApprovedEvent) event.getValue();
        assertThat(approved.getEventType()).isEqualTo("ORDER_APPROVED");
        assertThat(approved.getNotionalUsd()).isEqualByComparingTo(MAXIMUM_NOTIONAL_USD);
        assertThat(registry.find(MetricNames.ORDERS_REJECTED).counter()).isNull();
    }

    @Test
    void rejectsOrderAboveTheMaximumNotionalAndCountsTheReason() {
        control.onOrder(order(new BigDecimal("5000000.01")));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(Topics.ORDERS_REJECTED), eq("order-1"), event.capture());
        verify(kafka, never()).send(eq(Topics.ORDERS_APPROVED), anyString(), any());
        OrderRejectedEvent rejected = (OrderRejectedEvent) event.getValue();
        assertThat(rejected.getRejectionReason()).isEqualTo("DEMO_MAX_NOTIONAL_EXCEEDED");
        assertThat(registry.get(MetricNames.ORDERS_REJECTED)
                        .tag("currency_pair", "EUR/USD")
                        .tag("failure_category", "DEMO_MAX_NOTIONAL_EXCEEDED")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    private static OrderReceivedEvent order(BigDecimal notionalUsd) {
        Instant submittedAt = Instant.now();
        return OrderReceivedEvent.newBuilder()
                .setEventId("event-1")
                .setOrderId("order-1")
                .setEventType("ORDER_RECEIVED")
                .setEventVersion(1)
                .setOccurredAt(submittedAt)
                .setCurrencyPair("EUR/USD")
                .setDemoScenarioId(null)
                .setSide("BUY")
                .setQuantity(new BigDecimal("1000000.00"))
                .setLimitPrice(new BigDecimal("1.100000"))
                .setNotionalUsd(notionalUsd)
                .setSubmittedAt(submittedAt)
                .build();
    }
}
