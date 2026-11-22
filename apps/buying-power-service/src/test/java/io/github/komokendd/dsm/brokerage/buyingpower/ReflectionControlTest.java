package io.github.komokendd.dsm.brokerage.buyingpower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReflectionControlTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T00:00:00Z"));
    private final ReflectionControl control = new ReflectionControl(0, 2_500, clock);

    @Test
    void startsNormalWithoutDelay() {
        assertThat(control.status()).isEqualTo(new ReflectionControl.Status("NORMAL", 0, clock.instant()));
    }

    @Test
    void delayAndRecoverSwitchModeAndRecordTheAction() {
        clock.now = Instant.parse("2026-11-24T00:01:00Z");
        var slowed = control.injectDelay();
        assertThat(slowed.mode()).isEqualTo("SLOW_REFLECTION");
        assertThat(slowed.delayMillis()).isEqualTo(2_500);
        assertThat(slowed.lastActionAt()).isEqualTo(clock.now);
        assertThat(control.delayMillis()).isEqualTo(2_500);

        clock.now = Instant.parse("2026-11-24T00:02:00Z");
        var recovered = control.recover();
        assertThat(recovered).isEqualTo(new ReflectionControl.Status("NORMAL", 0, clock.now));
    }

    @Test
    void pauseReturnsImmediatelyWhenNormal() {
        long started = System.nanoTime();
        control.pause();
        assertThat(System.nanoTime() - started).isLessThan(100_000_000L);
    }

    @Test
    void negativeDelaysAreRejected() {
        assertThatThrownBy(() -> new ReflectionControl(-1, 2_500, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
