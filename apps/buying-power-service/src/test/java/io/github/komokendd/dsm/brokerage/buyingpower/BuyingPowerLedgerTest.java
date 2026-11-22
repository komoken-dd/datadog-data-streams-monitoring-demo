package io.github.komokendd.dsm.brokerage.buyingpower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.brokerage.common.EventIds;
import io.github.komokendd.dsm.brokerage.common.IdempotentEventStore;
import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.BuyingPowerUpdatedEvent;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class BuyingPowerLedgerTest {
    private static final Instant EXECUTED_AT = Instant.parse("2026-11-24T00:00:01Z");
    private static final Instant NOW = Instant.parse("2026-11-24T00:00:03.500Z");

    private final BuyingPowerRepository repository = mock(BuyingPowerRepository.class);
    private final IdempotentEventStore processed = mock(IdempotentEventStore.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, BuyingPowerUpdatedEvent> kafka = mock(KafkaTemplate.class);
    private final BuyingPowerLedger ledger =
            new BuyingPowerLedger(repository, processed, kafka, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.applyToAccount(anyString(), any())).thenReturn(true);
        when(repository.findAccount("A00451")).thenReturn(Optional.of(new BuyingPowerRepository.AccountSnapshot(
                "A00451", Jpy.of(1_250_000), Jpy.of(0), NOW)));
    }

    @Test
    void sellFillIsReflectedAndPublishedKeyedByAccount() {
        when(processed.claim("exec-event-1")).thenReturn(true);
        when(repository.lockReservation("o-2")).thenReturn(Optional.of(BuyingPowerRulesTest.sellReservation(100)));

        var published = ledger.applyExecution(sellExecution());

        var deltaCaptor = ArgumentCaptor.forClass(LedgerDelta.class);
        verify(repository).applyToAccount(eq("A00451"), deltaCaptor.capture());
        assertThat(deltaCaptor.getValue().cashDeltaJpy()).isEqualByComparingTo("1250000");
        verify(repository).applyToSellable(eq("A00451"), eq("D105"), any());
        verify(repository).applyToReservation(eq("o-2"), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, BuyingPowerUpdatedEvent>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(record.capture());
        assertThat(record.getValue().topic()).isEqualTo(Topics.BUYING_POWER_UPDATED);
        assertThat(record.getValue().key()).isEqualTo("A00451");
        var event = record.getValue().value();
        assertThat(published).contains(event);
        assertThat(event.getEventId()).isEqualTo(EventIds.derived("exec-event-1", "BUYING_POWER_UPDATED"));
        assertThat(event.getSourceEventId()).isEqualTo("exec-event-1");
        assertThat(event.getSourceEventType()).isEqualTo("EXECUTION_REPORTED");
        assertThat(event.getSourceOccurredAt()).isEqualTo(EXECUTED_AT);
        assertThat(event.getReflectedAt()).isEqualTo(NOW);
        assertThat(event.getBuyingPowerJpy()).isEqualByComparingTo("1250000");
        assertThat(event.getDemoScenarioId()).isEqualTo("switch-trade");
    }

    @Test
    void duplicateExecutionIsSkippedWithoutSideEffects() {
        when(processed.claim("exec-event-1")).thenReturn(false);

        assertThat(ledger.applyExecution(sellExecution())).isEmpty();

        verify(repository, never()).lockReservation(anyString());
        verify(repository, never()).applyToAccount(anyString(), any());
        verifyNoInteractions(kafka);
    }

    @Test
    void expiryReleasesRemainingReservation() {
        when(processed.claim("expiry-event-1")).thenReturn(true);
        when(repository.lockReservation("o-1")).thenReturn(Optional.of(new ReservationState(
                "o-1", "A00451", "D101", io.github.komokendd.dsm.brokerage.common.Side.BUY, "LIMIT", 300,
                Jpy.of(840_000), Jpy.of(280_000), 0, 0, ReservationState.Status.RESERVED)));

        var published = ledger.applyExpiry(expiry());

        var deltaCaptor = ArgumentCaptor.forClass(LedgerDelta.class);
        verify(repository).applyToAccount(eq("A00451"), deltaCaptor.capture());
        assertThat(deltaCaptor.getValue().reservedDeltaJpy()).isEqualByComparingTo("-560000");
        assertThat(deltaCaptor.getValue().reservationStatus()).isEqualTo(ReservationState.Status.RELEASED);
        assertThat(published).hasValueSatisfying(event -> {
            assertThat(event.getSourceEventType()).isEqualTo("ORDER_EXPIRED");
            assertThat(event.getSourceOccurredAt()).isEqualTo(EXECUTED_AT);
        });
    }

    @Test
    void duplicateExpiryIsSkipped() {
        when(processed.claim("expiry-event-1")).thenReturn(false);

        assertThat(ledger.applyExpiry(expiry())).isEmpty();
        verifyNoInteractions(kafka);
    }

    @Test
    void unknownAccountIsNotPublished() {
        when(processed.claim("exec-event-1")).thenReturn(true);
        when(repository.lockReservation("o-2")).thenReturn(Optional.empty());
        when(repository.applyToAccount(anyString(), any())).thenReturn(false);

        assertThat(ledger.applyExecution(sellExecution())).isEmpty();
        verifyNoInteractions(kafka);
    }

    private static ExecutionReportEvent sellExecution() {
        return ExecutionReportEvent.newBuilder()
                .setEventId("exec-event-1")
                .setOccurredAt(EXECUTED_AT)
                .setAccountId("A00451")
                .setOrderId("o-2")
                .setSymbol("D105")
                .setSide("SELL")
                .setDemoScenarioId("switch-trade")
                .setOrderType("MARKET")
                .setQuantity(100)
                .setLimitPrice(null)
                .setReservedAmountJpy(Jpy.of(0))
                .setExecutionId("x-1")
                .setExecutionSequence(1)
                .setExecutedQuantity(100)
                .setExecutionPrice(Jpy.price(new BigDecimal("12500")))
                .setExecutionAmountJpy(Jpy.of(1_250_000))
                .setCumulativeQuantity(100)
                .setLeavesQuantity(0)
                .setOrderStatus("FILLED")
                .setAcceptedAt(EXECUTED_AT.minusMillis(200))
                .setExecutedAt(EXECUTED_AT)
                .build();
    }

    private static OrderExpiredEvent expiry() {
        return OrderExpiredEvent.newBuilder()
                .setEventId("expiry-event-1")
                .setOccurredAt(EXECUTED_AT)
                .setAccountId("A00451")
                .setOrderId("o-1")
                .setSymbol("D101")
                .setSide("BUY")
                .setDemoScenarioId(null)
                .setOrderType("LIMIT")
                .setQuantity(300)
                .setLimitPrice(Jpy.price(new BigDecimal("2800")))
                .setReservedAmountJpy(Jpy.of(840_000))
                .setCumulativeQuantity(100)
                .setExpiredQuantity(200)
                .setReason("SESSION_CLOSE")
                .setAcceptedAt(EXECUTED_AT.minusSeconds(60))
                .setExpiredAt(EXECUTED_AT)
                .build();
    }
}
