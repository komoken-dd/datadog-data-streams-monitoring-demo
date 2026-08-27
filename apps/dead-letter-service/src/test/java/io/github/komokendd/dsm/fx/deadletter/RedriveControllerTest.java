package io.github.komokendd.dsm.fx.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.fx.schema.OrderApprovedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

class RedriveControllerTest {
    @Test
    void succeedsWithoutPublishingWhenThereAreNoUnresolvedDeadLetters() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, OrderApprovedEvent> kafka = mock(KafkaTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers
                .<org.springframework.jdbc.core.RowMapper<RedriveController.DeadLetterRow>>any()))
                .thenReturn(List.of());

        var result = new RedriveController(jdbc, kafka).redrive();

        assertThat(result.redrivenCount()).isZero();
        verify(kafka, never()).send(org.mockito.ArgumentMatchers
                .<ProducerRecord<String, OrderApprovedEvent>>any());
    }

    @Test
    void redrivesEveryUnresolvedDeadLetterAcrossScenarios() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, OrderApprovedEvent> kafka = mock(KafkaTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers
                .<org.springframework.jdbc.core.RowMapper<RedriveController.DeadLetterRow>>any())).thenReturn(List.of(
                row("event-1", "order-1", "scenario-1"),
                row("event-2", "order-2", "scenario-2")));
        when(kafka.send(org.mockito.ArgumentMatchers
                .<ProducerRecord<String, OrderApprovedEvent>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        var result = new RedriveController(jdbc, kafka).redrive();

        assertThat(result.redrivenCount()).isEqualTo(2);
        verify(kafka, org.mockito.Mockito.times(2)).send(org.mockito.ArgumentMatchers
                .<ProducerRecord<String, OrderApprovedEvent>>any());
        verify(jdbc).update(anyString(), anyString(), eq("event-1"));
        verify(jdbc).update(anyString(), anyString(), eq("event-2"));
    }

    private static RedriveController.DeadLetterRow row(String eventId, String orderId, String scenarioId) {
        Instant submittedAt = Instant.parse("2026-08-28T00:00:00Z");
        return new RedriveController.DeadLetterRow(
                eventId,
                orderId,
                "EUR/USD",
                "BUY",
                new BigDecimal("100000.00"),
                new BigDecimal("1.100000"),
                new BigDecimal("110000.00"),
                submittedAt,
                submittedAt.plusSeconds(1),
                scenarioId);
    }
}
