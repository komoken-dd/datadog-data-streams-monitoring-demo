package io.github.komokendd.dsm.brokerage.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExceptionControllerTest {
    private static final Instant NOW = Instant.parse("2026-11-20T00:15:00Z");

    @Test
    void listsUnresolvedDeadLettersOldestFirst() {
        var repository = mock(NotificationDeadLetterRepository.class);
        when(repository.findUnresolved()).thenReturn(List.of(
                DeadLetters.row("dead-1", "notification-1", NOW.minusSeconds(90)),
                DeadLetters.row("dead-2", "notification-2", NOW.minusSeconds(30))));
        var controller = new ExceptionController(
                repository, mock(RedriveService.class), Clock.fixed(NOW, ZoneOffset.UTC));

        var result = controller.unresolved();

        assertThat(result.count()).isEqualTo(2);
        assertThat(result.asOf()).isEqualTo(NOW);
        assertThat(result.exceptions()).extracting(NotificationDeadLetter::notificationId)
                .containsExactly("notification-1", "notification-2");
    }
}
