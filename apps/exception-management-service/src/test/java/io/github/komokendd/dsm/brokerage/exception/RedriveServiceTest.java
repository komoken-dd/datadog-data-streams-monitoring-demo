package io.github.komokendd.dsm.brokerage.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.brokerage.common.KafkaHeaders;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class RedriveServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-20T00:15:00Z");

    private NotificationDeadLetterRepository repository;
    private KafkaTemplate<String, Object> kafka;
    private RedriveService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(NotificationDeadLetterRepository.class);
        kafka = mock(KafkaTemplate.class);
        AtomicInteger ids = new AtomicInteger();
        service = new RedriveService(repository, kafka, Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "redrive-" + ids.incrementAndGet());
    }

    @Test
    void succeedsWithoutPublishingWhenThereIsNothingToRedrive() throws Exception {
        when(repository.lockUnresolved()).thenReturn(List.of());

        var result = service.redrive();

        assertThat(result.redrivenCount()).isZero();
        assertThat(result.completedAt()).isEqualTo(NOW);
        verify(kafka, never()).send(any(ProducerRecord.class));
        verify(repository, never()).markResolved(anyString(), anyString());
    }

    @Test
    void republishesEachUnresolvedRequestWithTheSameNotificationIdAndRedriveHeaders() throws Exception {
        when(repository.lockUnresolved()).thenReturn(List.of(
                DeadLetters.row("dead-1", "notification-1", NOW.minusSeconds(90)),
                DeadLetters.row("dead-2", "notification-2", NOW.minusSeconds(30))));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        var result = service.redrive();

        assertThat(result.redrivenCount()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(captor.capture());
        ProducerRecord<String, Object> first = captor.getAllValues().getFirst();
        assertThat(first.topic()).isEqualTo(Topics.NOTIFICATIONS_RETRY_5S);
        assertThat(first.key()).isEqualTo("A00001");
        NotificationRequest request = (NotificationRequest) first.value();
        assertThat(request.getEventId()).isEqualTo("redrive-1").isNotEqualTo("dead-1");
        assertThat(request.getNotificationId()).isEqualTo("notification-1");
        assertThat(request.getSourceEventId()).isEqualTo("exec-event-1");
        assertThat(request.getSourceOccurredAt()).isEqualTo(DeadLetters.EXECUTED_AT);
        assertThat(request.getPrice()).isEqualByComparingTo("9500");
        assertThat(request.getRequestedAt()).isEqualTo(NOW);
        assertThat(header(first, KafkaHeaders.REDRIVEN)).isEqualTo("true");
        assertThat(header(first, KafkaHeaders.RETRY_COUNT)).isEqualTo("0");
        assertThat(header(first, KafkaHeaders.ORIGINATING_TOPIC)).isEqualTo(Topics.NOTIFICATIONS_DLT);
        assertThat(((NotificationRequest) captor.getAllValues().get(1).value()).getNotificationId())
                .isEqualTo("notification-2");

        var order = inOrder(kafka, repository);
        order.verify(kafka).send(any(ProducerRecord.class));
        order.verify(repository).markResolved("dead-1", "redrive-1");
        order.verify(kafka).send(any(ProducerRecord.class));
        order.verify(repository).markResolved("dead-2", "redrive-2");
    }

    @Test
    void leavesTheDeadLetterUnresolvedWhenKafkaDoesNotAcknowledge() {
        when(repository.lockUnresolved()).thenReturn(List.of(
                DeadLetters.row("dead-1", "notification-1", NOW.minusSeconds(90))));
        CompletableFuture<SendResult<String, Object>> failed =
                CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed);

        assertThatThrownBy(() -> service.redrive()).hasRootCauseMessage("broker unavailable");

        verify(repository, never()).markResolved(anyString(), anyString());
    }

    private static String header(ProducerRecord<String, Object> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
