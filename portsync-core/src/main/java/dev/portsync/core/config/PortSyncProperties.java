package dev.portsync.core.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Every tunable of the core service, bound from {@code portsync.*} in application.yml.
 * Validated at startup so a typo in a threshold fails fast instead of silently disabling a rule.
 */
@Validated
@ConfigurationProperties(prefix = "portsync")
public record PortSyncProperties(
        @Valid @NotNull Clock clock,
        @Valid @NotNull Sla sla,
        @Valid @NotNull Consumer consumer,
        @Valid @NotNull Retention retention) {

    /**
     * Simulated clock. The core derives "now" from the newest event time it has seen and
     * extrapolates at {@code speedFactor}, but never further than {@code maxExtrapolation} past the
     * last event, so pausing the simulator also pauses SLA timers.
     */
    public record Clock(@Min(1) double speedFactor, @NotNull Duration maxExtrapolation) {
    }

    public record Sla(
            @NotNull Duration evaluationInterval,
            @Valid @NotNull CraneProductivity craneProductivity,
            @Valid @NotNull MaxDuration craneFault,
            @Valid @NotNull MaxDuration berthWait,
            @Valid @NotNull MaxDuration truckTurn,
            @Valid @NotNull YardUtilisation yardUtilisation) {
    }

    /** Alert when a working crane averages fewer than {@code minMovesPerHour} over {@code window}. */
    public record CraneProductivity(@Min(1) int minMovesPerHour, @NotNull Duration window) {
    }

    /** Alert when something has lasted longer than {@code max}. */
    public record MaxDuration(@NotNull Duration max) {
    }

    /** Alert when a block's occupied/capacity ratio is above {@code maxRatio}; critical at {@code criticalRatio}. */
    public record YardUtilisation(
            @DecimalMin("0.0") @DecimalMax("1.0") double maxRatio,
            @DecimalMin("0.0") @DecimalMax("1.0") double criticalRatio) {
    }

    /**
     * @param groupId       consumer group shared by the four topic listeners
     * @param concurrency   consumer threads per topic (one per partition)
     * @param maxRetries    retries for unexpected errors before PROCESSING_FAILED
     * @param graceRetries  retries for rejections that another topic's event could still resolve
     * @param graceBackoff  first pause of the grace period; doubles each retry, capped at 2 s
     */
    public record Consumer(@NotNull String groupId, @Min(1) int concurrency, @Min(0) int maxRetries,
                           @Min(0) int graceRetries, @NotNull Duration graceBackoff) {
    }

    /** How long processed-event ids are kept for idempotency checks (wall-clock time). */
    public record Retention(@NotNull Duration processedEvents, @NotNull Duration purgeInterval) {
    }
}
