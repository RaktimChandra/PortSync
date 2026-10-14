package dev.portsync.core.api;

import dev.portsync.core.clock.SimulatedClock;
import dev.portsync.core.ops.ConsumerLagMonitor;
import dev.portsync.core.ops.InvariantMonitor;
import dev.portsync.core.ops.ProcessingStats;
import dev.portsync.core.sla.SlaEvaluator;
import dev.portsync.core.store.YardRepository;
import dev.portsync.core.stream.SseHub;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Operator endpoints: processing statistics, invariant audit, SLA rule catalogue, clock state. */
@RestController
@RequestMapping("/api/ops")
@Tag(name = "Operations", description = "Statistics, invariant audit and runtime state for operators")
public class OpsController {

    private final ProcessingStats stats;
    private final InvariantMonitor invariants;
    private final ConsumerLagMonitor lag;
    private final SlaEvaluator sla;
    private final SimulatedClock clock;
    private final SseHub hub;

    public OpsController(ProcessingStats stats, InvariantMonitor invariants, ConsumerLagMonitor lag, SlaEvaluator sla,
                         SimulatedClock clock, SseHub hub) {
        this.stats = stats;
        this.invariants = invariants;
        this.lag = lag;
        this.sla = sla;
        this.clock = clock;
        this.hub = hub;
    }

    @GetMapping("/stats")
    @Operation(summary = "Events processed and latency percentiles since the last reset (used by the load test)")
    public ProcessingStats.Snapshot stats() {
        return stats.snapshot();
    }

    @PostMapping("/stats/reset")
    @Operation(summary = "Reset the statistics window")
    public ProcessingStats.Snapshot resetStats() {
        stats.reset();
        return stats.snapshot();
    }

    @GetMapping("/invariants")
    @Operation(summary = "Audit the yard invariants right now; an empty list means the yard is consistent")
    public InvariantReport invariants() {
        List<YardRepository.InvariantViolation> found = invariants.check();
        return new InvariantReport(found.isEmpty(), found);
    }

    @GetMapping("/runtime")
    @Operation(summary = "Simulated clock, SLA rules, consumer lag and connected dashboards")
    public RuntimeInfo runtime() {
        return new RuntimeInfo(clock.now(), clock.watermark(), clock.speedFactor(), clock.isSynchronised(),
                sla.lastEvaluatedAt(),
                sla.rules().stream().map(r -> new Rule(r.id(), r.description())).toList(),
                lag.lag(), hub.clientCount(), hub.droppedMessages());
    }

    public record InvariantReport(boolean consistent, List<YardRepository.InvariantViolation> violations) {
    }

    public record Rule(String id, String description) {
    }

    public record RuntimeInfo(Instant simulatedNow, Instant watermark, double speedFactor, boolean synchronised,
                          Instant slaLastEvaluatedAt, List<Rule> slaRules, Map<String, Long> consumerLag,
                          int sseClients, long sseDroppedMessages) {
    }
}
