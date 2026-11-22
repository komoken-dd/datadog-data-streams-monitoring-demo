package io.github.komokendd.dsm.brokerage.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.common.KafkaHeaders;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.BuyingPowerUpdatedEvent;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import io.github.komokendd.dsm.brokerage.schema.TradeReportIssuedEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderStateProjectionTest {
    private static final Instant AT = Instant.parse("2026-11-20T01:00:02Z");
    private final OrderStateStore store = mock(OrderStateStore.class);
    private final OrderStateProjection projection = new OrderStateProjection(store);

    @BeforeEach
    void claimsSucceed() {
        when(store.claim(anyString(), anyString())).thenReturn(true);
        when(store.applyMark(anyString(), anyString(), any()))
                .thenReturn(new OrderStateStore.MarkResult(false, null));
    }

    @Test
    void buyingPowerMarksTheSourceExecutionEvent() {
        projection.buyingPowerUpdated(buyingPower("EXECUTION_REPORTED"));

        var mark = ArgumentCaptor.forClass(ExecutionMark.class);
        verify(store).applyMark(eq("O-1"), eq("exec-event-1"), mark.capture());
        assertThat(mark.getValue().type()).isEqualTo(ExecutionMark.Type.BUYING_POWER_REFLECTED);
        assertThat(mark.getValue().at()).isEqualTo(AT);
    }

    @Test
    void buyingPowerReleasedByAnExpiryIsNotAnExecutionOutcome() {
        projection.buyingPowerUpdated(buyingPower("ORDER_EXPIRED"));

        verify(store, never()).applyMark(anyString(), anyString(), any());
    }

    @Test
    void tradeReportMarksByExecutionId() {
        projection.tradeReportIssued(TradeReportIssuedEvent.newBuilder()
                .setEventId("tr-event-1").setOccurredAt(AT).setAccountId("A00001").setOrderId("O-1")
                .setSymbol("D101").setSide("BUY").setExecutionId("X-1").setTradeReportId("TR-1")
                .setTradeDate("2026-11-20").setSettlementDate("2026-11-25").setExecutedQuantity(100)
                .setExecutionPrice(Jpy.price(new BigDecimal("2800"))).setExecutionAmountJpy(Jpy.of(280_000))
                .setCommissionJpy(Jpy.of(0)).setSettlementAmountJpy(Jpy.of(280_000))
                .setExecutedAt(AT).setIssuedAt(AT).build());

        verify(store).applyMark(eq("O-1"), eq("X-1"), any());
    }

    @Test
    void redrivenRetryUsesItsOwnTransitionKey() {
        var record = new ConsumerRecord<>(Topics.NOTIFICATIONS_RETRY_5S, 0, 0L, "A00001",
                notification("EXECUTION"));
        record.headers().add(KafkaHeaders.RETRY_COUNT, "0".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.REDRIVEN, "true".getBytes(StandardCharsets.UTF_8));

        projection.notificationRetryFive(record);

        verify(store).claim("notif-event-1", "retry-5s-0-redriven");
        var mark = ArgumentCaptor.forClass(ExecutionMark.class);
        verify(store).applyMark(eq("O-1"), eq("exec-event-1"), mark.capture());
        assertThat(mark.getValue().type()).isEqualTo(ExecutionMark.Type.NOTIFICATION_RETRYING);
        assertThat(mark.getValue().redriven()).isTrue();
    }

    @Test
    void deadLetteredExpiryNotificationIsIgnored() {
        projection.notificationDeadLetter(new ConsumerRecord<>(Topics.NOTIFICATIONS_DLT, 0, 0L, "A00001",
                notification("EXPIRY")));

        verify(store, never()).applyMark(anyString(), anyString(), any());
    }

    private static BuyingPowerUpdatedEvent buyingPower(String sourceEventType) {
        return BuyingPowerUpdatedEvent.newBuilder()
                .setEventId("bp-event-1").setOccurredAt(AT).setAccountId("A00001").setOrderId("O-1")
                .setSymbol("D101").setSide("BUY").setSourceEventId("exec-event-1")
                .setSourceEventType(sourceEventType).setSourceOccurredAt(AT)
                .setCashBalanceJpy(Jpy.of(1)).setReservedAmountJpy(Jpy.of(0)).setBuyingPowerJpy(Jpy.of(1))
                .setReflectedAt(AT).build();
    }

    private static NotificationRequest notification(String type) {
        return NotificationRequest.newBuilder()
                .setEventId("notif-event-1").setOccurredAt(AT).setAccountId("A00001").setOrderId("O-1")
                .setSymbol("D101").setSide("BUY").setNotificationId("N-1").setSourceEventId("exec-event-1")
                .setNotificationType(type).setQuantity(100).setSourceOccurredAt(AT).setRequestedAt(AT).build();
    }
}
