package io.github.komokendd.dsm.fx.generator;

import io.github.komokendd.dsm.fx.common.DemoScenarios;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class GeneratorControl {
    private final GeneratorProperties properties;
    private final Duration surgeDuration;
    private final Clock clock;
    private final AtomicReference<TrafficState> trafficState;
    private final AtomicReference<String> failureScenario = new AtomicReference<>();
    private final AtomicInteger markedOrdersRemaining = new AtomicInteger();
    private final AtomicReference<Instant> lastActionAt;

    @Autowired
    public GeneratorControl(MeterRegistry registry, GeneratorProperties properties) {
        this(registry, properties, Clock.systemUTC());
    }

    GeneratorControl(MeterRegistry registry, GeneratorProperties properties, Clock clock) {
        this.properties = properties;
        this.surgeDuration = Duration.ofSeconds(properties.surgeDurationSeconds());
        this.clock = clock;
        Instant startedAt = clock.instant();
        this.trafficState = new AtomicReference<>(TrafficState.baseline());
        this.lastActionAt = new AtomicReference<>(startedAt);
        Gauge.builder("demo.fx.scenario.current", this, control ->
                        switch (control.mode()) {
                            case STOPPED -> -1;
                            case BASELINE -> 0;
                            case SURGE -> 1;
                        })
                .description("Current generator mode: -1 stopped, 0 baseline, 1 surge")
                .register(registry);
    }

    public GeneratorStatus baseline() {
        trafficState.set(TrafficState.baseline());
        lastActionAt.set(clock.instant());
        return status();
    }

    public GeneratorStatus stop() {
        trafficState.set(TrafficState.stopped());
        lastActionAt.set(clock.instant());
        return status();
    }

    public GeneratorStatus surge() {
        Instant now = clock.instant();
        trafficState.set(new TrafficState(GeneratorMode.SURGE, now.plus(surgeDuration)));
        lastActionAt.set(now);
        return status();
    }

    public GeneratorStatus markNextTenFailures() {
        String scenarioId = DemoScenarios.PROCESSING_FAILURE_PREFIX + UUID.randomUUID();
        failureScenario.set(scenarioId);
        markedOrdersRemaining.set(10);
        lastActionAt.set(clock.instant());
        return status();
    }

    public String claimScenarioForNextOrder() {
        while (true) {
            int remaining = markedOrdersRemaining.get();
            if (remaining <= 0) {
                return null;
            }
            if (markedOrdersRemaining.compareAndSet(remaining, remaining - 1)) {
                return failureScenario.get();
            }
        }
    }

    public GeneratorMode mode() {
        return currentTrafficState().mode();
    }

    public int requestsPerSecond() {
        return switch (mode()) {
            case STOPPED -> 0;
            case BASELINE -> properties.baselinePerSecond();
            case SURGE -> properties.surgePerSecond();
        };
    }

    public GeneratorStatus status() {
        TrafficState current = currentTrafficState();
        return new GeneratorStatus(
                current.mode(),
                switch (current.mode()) {
                    case STOPPED -> 0;
                    case BASELINE -> properties.baselinePerSecond();
                    case SURGE -> properties.surgePerSecond();
                },
                current.surgeEndsAt(),
                failureScenario.get(),
                markedOrdersRemaining.get(),
                lastActionAt.get());
    }

    private TrafficState currentTrafficState() {
        while (true) {
            TrafficState current = trafficState.get();
            if (current.mode() != GeneratorMode.SURGE
                    || clock.instant().isBefore(current.surgeEndsAt())) {
                return current;
            }
            TrafficState baseline = TrafficState.baseline();
            if (trafficState.compareAndSet(current, baseline)) {
                lastActionAt.set(current.surgeEndsAt());
                return baseline;
            }
        }
    }

    public record GeneratorStatus(
            GeneratorMode mode,
            int targetRequestsPerSecond,
            Instant surgeEndsAt,
            String demoScenarioId,
            int markedOrdersRemaining,
            Instant lastActionAt) {
    }

    private record TrafficState(GeneratorMode mode, Instant surgeEndsAt) {
        private static TrafficState stopped() {
            return new TrafficState(GeneratorMode.STOPPED, null);
        }

        private static TrafficState baseline() {
            return new TrafficState(GeneratorMode.BASELINE, null);
        }
    }
}
