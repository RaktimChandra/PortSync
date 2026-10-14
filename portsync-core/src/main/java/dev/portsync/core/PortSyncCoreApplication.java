package dev.portsync.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PortSync core service: consumes terminal events from Kafka, keeps terminal state in PostgreSQL,
 * evaluates SLA rules against the simulated clock, and serves the REST API and live SSE stream.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class PortSyncCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortSyncCoreApplication.class, args);
    }
}
