package dev.portsync.simulator;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Simulation parameters (simulator.* in application.yml). All durations are SIMULATED time unless
 * the name says otherwise.
 *
 * @param speedFactor           simulated seconds per real second (60 = one simulated minute per second)
 * @param start                 simulated start time; null = wall clock at startup
 * @param seed                  random seed, so a run can be reproduced exactly
 * @param autoStart             start ticking immediately at startup
 * @param seedInitialYard       publish an opening inventory before the first tick
 * @param initialFill           block -> share of slots filled by the opening inventory
 * @param vesselInterarrival    mean time between vessel arrivals
 * @param pilotageDelay         minimum time between arrival and berthing
 * @param minDischarge          lower bound of containers to discharge per vessel
 * @param maxDischarge          upper bound
 * @param minLoad               lower bound of containers to load per vessel
 * @param maxLoad               upper bound
 * @param craneMovesPerHour     average productivity of a healthy crane
 * @param trucksPerHour         average truck arrivals at the gate
 * @param pickupShare           share of trucks that collect an import (the rest drop off exports)
 * @param slowTruckProbability  share of trucks that take abnormally long inside the terminal
 * @param craneFaultsPerHour    probability per working crane per hour of a random fault
 * @param heartbeatInterval     time between crane heartbeats
 */
@Validated
@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(
        @Min(1) double speedFactor,
        Instant start,
        long seed,
        boolean autoStart,
        boolean seedInitialYard,
        @NotNull Map<String, Double> initialFill,
        @NotNull Duration vesselInterarrival,
        @NotNull Duration pilotageDelay,
        @Min(0) int minDischarge,
        @Min(0) int maxDischarge,
        @Min(0) int minLoad,
        @Min(0) int maxLoad,
        @Min(1) double craneMovesPerHour,
        @Min(0) double trucksPerHour,
        @DecimalMin("0.0") @DecimalMax("1.0") double pickupShare,
        @DecimalMin("0.0") @DecimalMax("1.0") double slowTruckProbability,
        @DecimalMin("0.0") double craneFaultsPerHour,
        @NotNull Duration heartbeatInterval) {
}
