package dev.portsync.core.clock;

import dev.portsync.core.config.PortSyncProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The core service's view of simulated time ("event time").
 *
 * <p>The simulator runs a clock that is N times faster than wall time and stamps every event with
 * it. The core never shares that clock directly; instead it keeps a <em>watermark</em> (the newest
 * {@code occurredAt} seen) and extrapolates from it:
 *
 * <pre>
 *   now = watermark + min((wallNow - wallTimeWatermarkWasSeen) * speedFactor, maxExtrapolation)
 * </pre>
 *
 * <ul>
 *   <li>Crane heartbeats arrive every few simulated minutes, so the watermark stays fresh.</li>
 *   <li>Old events (out-of-order or replayed) never move the watermark backwards.</li>
 *   <li>If the simulator stops, time stops after {@code maxExtrapolation}, so SLA timers do not
 *       run away and raise false alerts while nothing is happening.</li>
 *   <li>{@link #now()} never goes backwards, even when a fresh watermark is slightly behind the
 *       previous extrapolation.</li>
 * </ul>
 *
 * Before any event has been seen the wall clock is used.
 */
@Component
public class SimulatedClock {

    private final Clock wallClock;
    private final double speedFactor;
    private final Duration maxExtrapolation;

    private Instant watermark;
    private Instant watermarkSeenAtWall;
    private Instant lastReturned = Instant.EPOCH;

    @Autowired
    public SimulatedClock(Clock wallClock, PortSyncProperties properties) {
        this(wallClock, properties.clock().speedFactor(), properties.clock().maxExtrapolation());
    }

    public SimulatedClock(Clock wallClock, double speedFactor, Duration maxExtrapolation) {
        this.wallClock = wallClock;
        this.speedFactor = speedFactor;
        this.maxExtrapolation = maxExtrapolation;
    }

    /** Feeds an event time into the clock. Only times newer than the watermark advance it. */
    public synchronized void observe(Instant occurredAt) {
        if (occurredAt == null) {
            return;
        }
        if (watermark == null || occurredAt.isAfter(watermark)) {
            watermark = occurredAt;
            watermarkSeenAtWall = wallClock.instant();
        }
    }

    /** Current simulated time. Monotonic. */
    public synchronized Instant now() {
        Instant computed;
        if (watermark == null) {
            computed = wallClock.instant();
        } else {
            long wallElapsedMillis = Math.max(0, Duration.between(watermarkSeenAtWall, wallClock.instant()).toMillis());
            long simElapsedMillis = (long) (wallElapsedMillis * speedFactor);
            long capped = Math.min(simElapsedMillis, maxExtrapolation.toMillis());
            computed = watermark.plusMillis(capped);
        }
        if (computed.isAfter(lastReturned)) {
            lastReturned = computed;
        }
        return lastReturned;
    }

    /** True once at least one event has been observed. */
    public synchronized boolean isSynchronised() {
        return watermark != null;
    }

    public synchronized Instant watermark() {
        return watermark;
    }

    public double speedFactor() {
        return speedFactor;
    }
}
