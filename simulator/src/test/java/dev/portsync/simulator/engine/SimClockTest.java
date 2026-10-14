package dev.portsync.simulator.engine;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SimClockTest {

    static final class ManualClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

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

    private final ManualClock wall = new ManualClock();
    private final Instant start = Instant.parse("2026-10-14T06:00:00Z");

    @Test
    void runsAtSpeedFactor() {
        SimClock clock = new SimClock(wall, 60, start);
        wall.now = wall.now.plusSeconds(10);
        assertThat(clock.now()).isEqualTo(start.plus(Duration.ofMinutes(10)));
    }

    @Test
    void pauseFreezesAndResumeContinuesFromThere() {
        SimClock clock = new SimClock(wall, 60, start);
        wall.now = wall.now.plusSeconds(1);
        clock.pause();
        wall.now = wall.now.plusSeconds(100);
        assertThat(clock.now()).isEqualTo(start.plus(Duration.ofMinutes(1)));
        clock.resume();
        wall.now = wall.now.plusSeconds(2);
        assertThat(clock.now()).isEqualTo(start.plus(Duration.ofMinutes(3)));
    }

    @Test
    void restartAt() {
        SimClock clock = new SimClock(wall, 60, start);
        wall.now = wall.now.plusSeconds(30);
        clock.restartAt(start);
        assertThat(clock.now()).isEqualTo(start);
    }
}
