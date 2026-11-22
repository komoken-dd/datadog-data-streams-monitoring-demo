package io.github.komokendd.dsm.brokerage.buyingpower;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demo control for scenario 2: slows the reflection of executions into buying power, so sale
 * proceeds arrive late and switch-trade BUY orders are rejected (「売ったのに買えない」).
 */
final class ReflectionControl {
    static final String NORMAL = "NORMAL";
    static final String SLOW_REFLECTION = "SLOW_REFLECTION";

    private final long normalDelayMillis;
    private final long slowDelayMillis;
    private final Clock clock;
    private final AtomicReference<Status> status;

    ReflectionControl(long normalDelayMillis, long slowDelayMillis, Clock clock) {
        if (normalDelayMillis < 0 || slowDelayMillis < 0) {
            throw new IllegalArgumentException("Reflection delays must not be negative");
        }
        this.normalDelayMillis = normalDelayMillis;
        this.slowDelayMillis = slowDelayMillis;
        this.clock = clock;
        this.status = new AtomicReference<>(new Status(NORMAL, normalDelayMillis, clock.instant()));
    }

    Status injectDelay() {
        return set(SLOW_REFLECTION, slowDelayMillis);
    }

    Status recover() {
        return set(NORMAL, normalDelayMillis);
    }

    Status status() {
        return status.get();
    }

    long delayMillis() {
        return status.get().delayMillis();
    }

    /** Applied before each execution or expiry record. */
    void pause() {
        long delay = delayMillis();
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Reflection delay interrupted", exception);
        }
    }

    private Status set(String mode, long delayMillis) {
        var next = new Status(mode, delayMillis, clock.instant());
        status.set(next);
        return next;
    }

    record Status(String mode, long delayMillis, Instant lastActionAt) {
    }
}
