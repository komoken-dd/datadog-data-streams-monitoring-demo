package io.github.komokendd.dsm.fx.execution;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class ExecutionControl {
    private final long normalDelayMillis;
    private final long slowDelayMillis;
    private final AtomicLong currentDelayMillis;
    private final AtomicReference<String> mode = new AtomicReference<>("NORMAL");
    private final AtomicReference<Instant> lastActionAt = new AtomicReference<>(Instant.now());

    ExecutionControl(
            @Value("${demo.execution.normal-delay-ms:75}") long normalDelayMillis,
            @Value("${demo.execution.slow-delay-ms:1500}") long slowDelayMillis) {
        this.normalDelayMillis = normalDelayMillis;
        this.slowDelayMillis = slowDelayMillis;
        this.currentDelayMillis = new AtomicLong(normalDelayMillis);
    }

    Status injectDelay() {
        currentDelayMillis.set(slowDelayMillis);
        mode.set("SLOW_EXECUTION");
        lastActionAt.set(Instant.now());
        return status();
    }

    Status recover() {
        currentDelayMillis.set(normalDelayMillis);
        mode.set("NORMAL");
        lastActionAt.set(Instant.now());
        return status();
    }

    long delayMillis() {
        return currentDelayMillis.get();
    }

    Status status() {
        return new Status(mode.get(), currentDelayMillis.get(), lastActionAt.get());
    }

    record Status(String mode, long delayMillis, Instant lastActionAt) {
    }
}

