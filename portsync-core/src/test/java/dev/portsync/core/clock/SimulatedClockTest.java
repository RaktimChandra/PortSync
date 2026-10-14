package dev.portsync.core.clock;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedClockTest {

    private static final Instant SIM = Instant.parse("2026-10-14T08:00:00Z");

    /** A wall clock the test can move forward. */
    static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final ManualClock wall = new ManualClock();
    private final SimulatedClock clock = new SimulatedClock(wall, 60, Duration.ofMinutes(10));

    @Test
    void usesWallClockUntilFirstEvent() {
        assertThat(clock.isSynchronised()).isFalse();
        assertThat(clock.now()).isEqualTo(wall.instant());
    }

    @Test
    void extrapolatesAtSpeedFactorFromWatermark() {
        clock.observe(SIM);
        wall.advance(Duration.ofSeconds(3)); // 3 real seconds x 60 = 3 simulated minutes
        assertThat(clock.now()).isEqualTo(SIM.plus(Duration.ofMinutes(3)));
    }

    @Test
    void extrapolationIsCappedSoAPausedSimulatorFreezesTime() {
        clock.observe(SIM);
        wall.advance(Duration.ofMinutes(5)); // would be 5 simulated hours
        assertThat(clock.now()).isEqualTo(SIM.plus(Duration.ofMinutes(10)));
    }

    @Test
    void olderEventsNeverMoveTheWatermarkBack() {
        clock.observe(SIM);
        clock.observe(SIM.minus(Duration.ofHours(2)));
        assertThat(clock.watermark()).isEqualTo(SIM);
    }

    @Test
    void nowIsMonotonicEvenWhenNewWatermarkIsBehindExtrapolation() {
        clock.observe(SIM);
        wall.advance(Duration.ofSeconds(5));
        Instant extrapolated = clock.now(); // SIM + 5 min
        clock.observe(SIM.plus(Duration.ofMinutes(2))); // newer than watermark, older than extrapolation
        assertThat(clock.now()).isEqualTo(extrapolated);
        wall.advance(Duration.ofSeconds(5)); // SIM + 2 min + 5 min
        assertThat(clock.now()).isEqualTo(SIM.plus(Duration.ofMinutes(7)));
    }
}
