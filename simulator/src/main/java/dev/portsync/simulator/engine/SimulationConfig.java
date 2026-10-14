package dev.portsync.simulator.engine;

import dev.portsync.simulator.SimulatorProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;

@Configuration
public class SimulationConfig {

    @Bean
    public SimClock simClock(SimulatorProperties props) {
        Instant start = props.start() != null ? props.start() : Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        return new SimClock(Clock.systemUTC(), props.speedFactor(), start);
    }

    @Bean
    public TerminalSimulation terminalSimulation(SimulatorProperties props, EventSink sink) {
        return new TerminalSimulation(props, sink);
    }
}
