package io.github.komokendd.dsm.brokerage.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmissionLaneTest {
    private static final long TICK_NANOS = 50_000_000L;

    @Test
    void emitsTheConfiguredRateAcrossTicks() {
        var lane = new EmissionLane();
        int emitted = 0;
        for (int tick = 0; tick < 200; tick++) {
            emitted += lane.due(tick * TICK_NANOS, 25);
        }
        // 200 ticks of 50 ms = 10 s at 25/s, plus the first order immediately.
        assertThat(emitted).isBetween(249, 251);
    }

    @Test
    void doesNotBurstAfterAStallOrWhenStopped() {
        var lane = new EmissionLane();
        lane.due(0, 3);
        assertThat(lane.due(60_000_000_000L, 3)).isEqualTo(3);
        assertThat(lane.due(61_000_000_000L, 0)).isZero();
        assertThat(lane.due(62_000_000_000L, 3)).isEqualTo(1);
    }
}
