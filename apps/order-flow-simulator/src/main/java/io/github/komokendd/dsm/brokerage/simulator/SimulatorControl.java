package io.github.komokendd.dsm.brokerage.simulator;

import io.github.komokendd.dsm.brokerage.common.DemoScenarios;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Traffic mode. Opening rush and sell-off are time-boxed and fall back to baseline on their own. */
@Component
public class SimulatorControl {
    private final SimulatorProperties properties;
    private final Clock clock;
    private final AtomicReference<State> state;
    private final AtomicReference<Instant> lastActionAt;

    public SimulatorControl(MeterRegistry registry, SimulatorProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.state = new AtomicReference<>(State.baseline());
        this.lastActionAt = new AtomicReference<>(clock.instant());
        Gauge.builder(MetricNames.SCENARIO_CURRENT, this, control -> control.current().mode().gaugeValue())
                .description("Current simulator mode: -1 stopped, 0 baseline, 1 opening rush, 2 sell-off")
                .register(registry);
    }

    public SimulatorStatus baseline() {
        return transition(State.baseline());
    }

    public SimulatorStatus stop() {
        return transition(new State(SimulatorMode.STOPPED, null, null));
    }

    public SimulatorStatus openingRush() {
        Instant endsAt = clock.instant().plus(Duration.ofSeconds(properties.openingRushSeconds()));
        return transition(new State(
                SimulatorMode.OPENING_RUSH, endsAt, DemoScenarios.OPENING_RUSH_PREFIX + UUID.randomUUID()));
    }

    public SimulatorStatus sellOff() {
        Instant endsAt = clock.instant().plus(Duration.ofSeconds(properties.sellOffSeconds()));
        return transition(new State(
                SimulatorMode.SELL_OFF, endsAt, DemoScenarios.SELL_OFF_PREFIX + UUID.randomUUID()));
    }

    /** Current state, returning to baseline once a time-boxed scenario has ended. */
    public State current() {
        while (true) {
            State current = state.get();
            if (current.endsAt() == null || clock.instant().isBefore(current.endsAt())) {
                return current;
            }
            State baseline = State.baseline();
            if (state.compareAndSet(current, baseline)) {
                lastActionAt.set(current.endsAt());
                return baseline;
            }
        }
    }

    /** Orders per second from regular accounts (baseline flow, or the opening rush). */
    public int regularRate(State current) {
        return switch (current.mode()) {
            case STOPPED -> 0;
            case BASELINE, SELL_OFF -> properties.baselinePerSecond();
            case OPENING_RUSH -> properties.openingRushPerSecond();
        };
    }

    /** Additional D105 sell orders per second on top of the baseline. */
    public int sellOffRate(State current) {
        return current.mode() == SimulatorMode.SELL_OFF ? properties.sellOffPerSecond() : 0;
    }

    public SimulatorStatus status() {
        State current = current();
        return new SimulatorStatus(
                current.mode(),
                regularRate(current) + sellOffRate(current),
                current.endsAt(),
                current.demoScenarioId(),
                lastActionAt.get());
    }

    private SimulatorStatus transition(State next) {
        state.set(next);
        lastActionAt.set(clock.instant());
        return status();
    }

    public record State(SimulatorMode mode, Instant endsAt, String demoScenarioId) {
        static State baseline() {
            return new State(SimulatorMode.BASELINE, null, null);
        }
    }

    public record SimulatorStatus(
            SimulatorMode mode,
            int targetOrdersPerSecond,
            Instant scenarioEndsAt,
            String demoScenarioId,
            Instant lastActionAt) {
    }
}
