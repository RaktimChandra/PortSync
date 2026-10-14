package dev.portsync.simulator.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Simulated clock: {@code now = start + (wall time running) x speedFactor}. Can be paused, in which
 * case simulated time stands still. Thread-safe.
 */
public class SimClock {

    private final Clock wall;
    private final double speedFactor;
    private Instant simAnchor;
    private Instant wallAnchor;
    private boolean paused;

    public SimClock(Clock wall, double speedFactor, Instant start) {
        this.wall = wall;
        this.speedFactor = speedFactor;
        this.simAnchor = start;
        this.wallAnchor = wall.instant();
    }

    public synchronized Instant now() {
        if (paused) {
            return simAnchor;
        }
        long wallMillis = Duration.between(wallAnchor, wall.instant()).toMillis();
        return simAnchor.plusMillis((long) (wallMillis * speedFactor));
    }

    public synchronized void pause() {
        if (!paused) {
            simAnchor = now();
            paused = true;
        }
    }

    public synchronized void resume() {
        if (paused) {
            wallAnchor = wall.instant();
            paused = false;
        }
    }

    /** Restarts the clock at a given simulated time (used once seeding has finished). */
    public synchronized void restartAt(Instant simTime) {
        simAnchor = simTime;
        wallAnchor = wall.instant();
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public double speedFactor() {
        return speedFactor;
    }
}
