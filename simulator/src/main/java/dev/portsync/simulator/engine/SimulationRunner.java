package dev.portsync.simulator.engine;

import dev.portsync.events.TerminalEvent;
import dev.portsync.simulator.SimulatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drives the simulation: publishes the opening inventory once (phase by phase), then advances the
 * simulation every real second to the simulated "now".
 */
@Component
public class SimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunner.class);
    /** Pause between opening-inventory phases so the consumer applies one topic's events before the next. */
    private static final long PHASE_PAUSE_MILLIS = 4_000;

    private final TerminalSimulation simulation;
    private final EventSink sink;
    private final SimClock clock;
    private final SimulatorProperties props;
    private final KafkaTemplate<String, String> kafka;
    private volatile boolean running;

    public SimulationRunner(TerminalSimulation simulation, EventSink sink, SimClock clock, SimulatorProperties props,
                            KafkaTemplate<String, String> kafka) {
        this.simulation = simulation;
        this.sink = sink;
        this.clock = clock;
        this.props = props;
        this.kafka = kafka;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.autoStart()) {
            log.info("Simulator ready; auto-start disabled. POST /api/simulation/resume to start.");
            clock.pause();
            return;
        }
        Thread.ofPlatform().name("simulation-bootstrap").daemon().start(this::bootstrap);
    }

    private void bootstrap() {
        Instant start = clock.now();
        try {
            if (props.seedInitialYard()) {
                List<List<TerminalEvent>> phases = simulation.openingInventory(start);
                int total = 0;
                for (List<TerminalEvent> phase : phases) {
                    phase.forEach(sink::publish);
                    kafka.flush();
                    total += phase.size();
                    TimeUnit.MILLISECONDS.sleep(PHASE_PAUSE_MILLIS);
                }
                log.info("Opening inventory published: {} events", total);
            }
            clock.restartAt(start);
            simulation.start(start);
            running = true;
            log.info("Simulation started at simulated time {} ({}x)", start, props.speedFactor());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Advances the simulation. The paused check and the tick happen under the simulation's lock,
     * the same lock {@link #pause()} takes, so a tick that has seen "not paused" finishes before
     * a pause returns, and no tick starts after it.
     */
    @Scheduled(fixedRate = 1_000)
    public void tick() {
        synchronized (simulation) {
            if (!running || clock.isPaused()) {
                return;
            }
            try {
                simulation.tick(clock.now());
            } catch (RuntimeException e) {
                log.error("Simulation tick failed", e);
            }
        }
    }

    /**
     * Freezes simulated time once any tick under way has finished: when this returns, the
     * simulation's state, and every event it will publish until it resumes, are final. (Pausing
     * the clock alone let a tick that had already checked the clock change the yard after the
     * pause was acknowledged; TestHarness's yard comparison saw the core one container ahead of
     * the status the pause returned.)
     */
    public void pause() {
        synchronized (simulation) {
            clock.pause();
        }
    }

    /** Starts ticking if auto-start was disabled. */
    public void startIfIdle() {
        if (!running) {
            clock.resume();
            Thread.ofPlatform().name("simulation-bootstrap").daemon().start(this::bootstrap);
        }
    }

    public boolean isRunning() {
        return running;
    }
}
