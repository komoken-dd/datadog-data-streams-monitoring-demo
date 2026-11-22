package io.github.komokendd.dsm.brokerage.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.common.DemoScenarios;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SimulatorControlTest {
    private static final Instant T0 = Instant.parse("2026-11-24T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SimulatorControl control = new SimulatorControl(registry, SimulatorProperties.defaults(), clock);

    @Test
    void startsInBaseline() {
        assertThat(control.status().mode()).isEqualTo(SimulatorMode.BASELINE);
        assertThat(control.status().targetOrdersPerSecond()).isEqualTo(3);
        assertThat(gauge()).isZero();
    }

    @Test
    void openingRushReturnsToBaselineAfterItsWindow() {
        var status = control.openingRush();

        assertThat(status.mode()).isEqualTo(SimulatorMode.OPENING_RUSH);
        assertThat(status.targetOrdersPerSecond()).isEqualTo(25);
        assertThat(status.scenarioEndsAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(DemoScenarios.isOpeningRush(status.demoScenarioId())).isTrue();
        assertThat(control.sellOffRate(control.current())).isZero();
        assertThat(gauge()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(59));
        assertThat(control.current().mode()).isEqualTo(SimulatorMode.OPENING_RUSH);

        clock.advance(Duration.ofSeconds(1));
        assertThat(control.current().mode()).isEqualTo(SimulatorMode.BASELINE);
        assertThat(control.status().lastActionAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(control.status().demoScenarioId()).isNull();
        assertThat(gauge()).isZero();
    }

    @Test
    void sellOffAddsSellFlowOnTopOfBaselineAndEndsAutomatically() {
        var status = control.sellOff();

        assertThat(status.mode()).isEqualTo(SimulatorMode.SELL_OFF);
        assertThat(DemoScenarios.isSellOff(status.demoScenarioId())).isTrue();
        assertThat(control.regularRate(control.current())).isEqualTo(3);
        assertThat(control.sellOffRate(control.current())).isEqualTo(25);
        assertThat(status.targetOrdersPerSecond()).isEqualTo(28);
        assertThat(gauge()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(120));
        assertThat(control.current().mode()).isEqualTo(SimulatorMode.BASELINE);
        assertThat(control.sellOffRate(control.current())).isZero();
    }

    @Test
    void eachScenarioRunGetsItsOwnId() {
        String first = control.sellOff().demoScenarioId();
        String second = control.sellOff().demoScenarioId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void stopIsNotTimeBoxedAndBaselineResumes() {
        control.openingRush();
        var stopped = control.stop();

        assertThat(stopped.mode()).isEqualTo(SimulatorMode.STOPPED);
        assertThat(stopped.targetOrdersPerSecond()).isZero();
        assertThat(gauge()).isEqualTo(-1);

        clock.advance(Duration.ofHours(1));
        assertThat(control.current().mode()).isEqualTo(SimulatorMode.STOPPED);

        assertThat(control.baseline().mode()).isEqualTo(SimulatorMode.BASELINE);
        assertThat(control.status().lastActionAt()).isEqualTo(T0.plus(Duration.ofHours(1)));
    }

    private double gauge() {
        return registry.get(MetricNames.SCENARIO_CURRENT).gauge().value();
    }
}
