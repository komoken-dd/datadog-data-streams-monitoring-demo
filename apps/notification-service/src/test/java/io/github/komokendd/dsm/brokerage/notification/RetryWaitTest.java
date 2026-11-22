package io.github.komokendd.dsm.brokerage.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetryWaitTest {
    private static final Instant NOW = Instant.parse("2026-11-20T00:01:00Z");

    @Test
    void waitsOnlyForTheRemainderOfTheTopicDelay() throws Exception {
        List<Duration> sleeps = new ArrayList<>();
        var wait = new RetryWait(Clock.fixed(NOW, ZoneOffset.UTC), sleeps::add);

        wait.awaitDue(NOW.minusSeconds(2).toEpochMilli(), Duration.ofSeconds(5));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void doesNotWaitForRecordsThatAreAlreadyDue() throws Exception {
        List<Duration> sleeps = new ArrayList<>();
        var wait = new RetryWait(Clock.fixed(NOW, ZoneOffset.UTC), sleeps::add);

        wait.awaitDue(NOW.minusSeconds(45).toEpochMilli(), Duration.ofSeconds(30));

        assertThat(sleeps).isEmpty();
    }

    @Test
    void neverWaitsLongerThanTheDelayWhenTheProducerClockIsAhead() {
        assertThat(RetryWait.remaining(NOW.plusSeconds(20), Duration.ofSeconds(5), NOW))
                .isEqualTo(Duration.ofSeconds(5));
    }
}
