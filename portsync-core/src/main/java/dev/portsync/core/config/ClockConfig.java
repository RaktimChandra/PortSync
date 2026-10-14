package dev.portsync.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wall clock as a bean so tests can substitute a fixed or adjustable clock. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock wallClock() {
        return Clock.systemUTC();
    }
}
