package io.github.komokendd.dsm.brokerage.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Holds a retry-topic record until it is due (record timestamp + topic delay).
 *
 * <p>This is a deliberately simple blocking wait so the 5 s / 30 s retry hops are visible on
 * the demo timeline (DSM pathway latency, consumer lag). A record that has already waited in the
 * topic longer than the delay is processed immediately, so a backlog drains at delivery speed.
 */
final class RetryWait {
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Clock clock;
    private final Sleeper sleeper;

    RetryWait(Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    void awaitDue(long recordTimestampMillis, Duration delay) throws InterruptedException {
        Duration remaining = remaining(Instant.ofEpochMilli(recordTimestampMillis), delay, clock.instant());
        if (remaining.isPositive()) {
            sleeper.sleep(remaining);
        }
    }

    static Duration remaining(Instant enqueuedAt, Duration delay, Instant now) {
        Duration remaining = Duration.between(now, enqueuedAt.plus(delay));
        // Clock skew between producer and consumer must never hold a record longer than the delay.
        return remaining.compareTo(delay) > 0 ? delay : remaining;
    }
}
