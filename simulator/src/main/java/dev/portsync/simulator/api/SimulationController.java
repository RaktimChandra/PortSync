package dev.portsync.simulator.api;

import dev.portsync.simulator.engine.SimClock;
import dev.portsync.simulator.engine.SimulationRunner;
import dev.portsync.simulator.engine.TerminalSimulation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulation")
@Tag(name = "Simulation", description = "Simulated clock and terminal state")
public class SimulationController {

    private final TerminalSimulation simulation;
    private final SimulationRunner runner;
    private final SimClock clock;

    public SimulationController(TerminalSimulation simulation, SimulationRunner runner, SimClock clock) {
        this.simulation = simulation;
        this.runner = runner;
        this.clock = clock;
    }

    @GetMapping("/status")
    @Operation(summary = "Simulated time, berths, cranes, yard occupancy and events published so far")
    public StatusView status() {
        return new StatusView(clock.now(), clock.speedFactor(), clock.isPaused(), runner.isRunning(), simulation.status());
    }

    @PostMapping("/pause")
    @Operation(summary = "Freeze simulated time (the core's clock stops too, after its extrapolation limit)")
    public StatusView pause() {
        runner.pause();
        return status();
    }

    @PostMapping("/resume")
    @Operation(summary = "Resume simulated time (or start it, if auto-start was disabled)")
    public StatusView resume() {
        clock.resume();
        runner.startIfIdle();
        return status();
    }

    public record StatusView(java.time.Instant simulatedNow, double speedFactor, boolean paused, boolean running,
                             TerminalSimulation.Status terminal) {
    }
}
