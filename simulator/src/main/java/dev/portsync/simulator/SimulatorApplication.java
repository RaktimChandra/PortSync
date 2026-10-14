package dev.portsync.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Terminal simulator: runs a container terminal on a fast simulated clock and publishes every
 * operational event to Kafka. Also exposes fault injection endpoints for demos and tests.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
