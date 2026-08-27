package io.github.komokendd.dsm.fx.generator;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class GeneratorControlTest {
    @Test
    void marksExactlyTenOrdersWithOneScenario() {
        var control = control(new MutableClock(Instant.parse("2026-08-28T00:00:00Z")));
        String scenario = control.markNextTenFailures().demoScenarioId();
        for (int index = 0; index < 10; index++) {
            assertThat(control.claimScenarioForNextOrder()).isEqualTo(scenario);
        }
        assertThat(control.claimScenarioForNextOrder()).isNull();
    }

    @Test
    void returnsToBaselineAfterNinetySeconds() {
        var clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        var control = control(clock);

        GeneratorControl.GeneratorStatus surge = control.surge();
        assertThat(surge.mode()).isEqualTo(GeneratorMode.SURGE);
        assertThat(surge.targetRequestsPerSecond()).isEqualTo(20);
        assertThat(surge.surgeEndsAt()).isEqualTo(Instant.parse("2026-08-28T00:01:30Z"));

        clock.advance(Duration.ofSeconds(89));
        assertThat(control.requestsPerSecond()).isEqualTo(20);

        clock.advance(Duration.ofSeconds(1));
        GeneratorControl.GeneratorStatus baseline = control.status();
        assertThat(baseline.mode()).isEqualTo(GeneratorMode.BASELINE);
        assertThat(baseline.targetRequestsPerSecond()).isEqualTo(2);
        assertThat(baseline.surgeEndsAt()).isNull();
    }

    @Test
    void manualBaselineUsesTheSameRateBeforeSurgeExpires() {
        var clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        var control = control(clock);

        control.surge();
        clock.advance(Duration.ofSeconds(30));
        GeneratorControl.GeneratorStatus baseline = control.baseline();

        assertThat(baseline.mode()).isEqualTo(GeneratorMode.BASELINE);
        assertThat(baseline.targetRequestsPerSecond()).isEqualTo(2);
        assertThat(baseline.surgeEndsAt()).isNull();

        clock.advance(Duration.ofSeconds(90));
        assertThat(control.requestsPerSecond()).isEqualTo(2);
    }

    @Test
    void stopCancelsTheCurrentTrafficModeUntilBaselineResumesIt() {
        var clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        var control = control(clock);

        control.surge();
        GeneratorControl.GeneratorStatus stopped = control.stop();

        assertThat(stopped.mode()).isEqualTo(GeneratorMode.STOPPED);
        assertThat(stopped.targetRequestsPerSecond()).isZero();
        assertThat(stopped.surgeEndsAt()).isNull();

        clock.advance(Duration.ofSeconds(90));
        assertThat(control.requestsPerSecond()).isZero();

        GeneratorControl.GeneratorStatus baseline = control.baseline();
        assertThat(baseline.mode()).isEqualTo(GeneratorMode.BASELINE);
        assertThat(baseline.targetRequestsPerSecond()).isEqualTo(2);
    }

    private static GeneratorControl control(Clock clock) {
        var properties = new GeneratorProperties("http://order-service:8080", 2, 20, 90, 20260819L);
        return new GeneratorControl(new SimpleMeterRegistry(), properties, clock);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
