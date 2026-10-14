package dev.portsync.core.sla;

import dev.portsync.core.alert.AlertReconciler;
import dev.portsync.core.alert.AlertRepository;
import dev.portsync.core.alert.AlertService;
import dev.portsync.core.clock.SimulatedClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Evaluates every SLA rule on a fixed wall-clock schedule against the SIMULATED "now", then
 * reconciles alerts. Skips evaluation until the first event has synchronised the clock.
 */
@Component
public class SlaEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SlaEvaluator.class);

    private final List<SlaRule> rules;
    private final SlaSnapshotLoader loader;
    private final AlertService alertService;
    private final AlertRepository alertRepository;
    private final SimulatedClock clock;
    private final Map<String, AtomicInteger> activeByRule = new LinkedHashMap<>();
    private volatile Instant lastEvaluatedAt;

    public SlaEvaluator(List<SlaRule> rules, SlaSnapshotLoader loader, AlertService alertService,
                        AlertRepository alertRepository, SimulatedClock clock, MeterRegistry registry) {
        this.rules = rules;
        this.loader = loader;
        this.alertService = alertService;
        this.alertRepository = alertRepository;
        this.clock = clock;
        for (SlaRule rule : rules) {
            AtomicInteger gauge = new AtomicInteger();
            activeByRule.put(rule.id(), gauge);
            Gauge.builder("portsync.alerts.active", gauge, AtomicInteger::get)
                    .description("Alerts currently OPEN or ACKNOWLEDGED, by rule")
                    .tag("rule", rule.id())
                    .register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${portsync.sla.evaluation-interval}",
            initialDelayString = "${portsync.sla.evaluation-interval}")
    public void scheduledEvaluation() {
        if (!clock.isSynchronised()) {
            return;
        }
        try {
            evaluate(clock.now());
        } catch (RuntimeException e) {
            // Never let one failed evaluation kill the scheduler thread; the next run retries.
            log.error("SLA evaluation failed", e);
        }
    }

    /** Runs every rule once at the given simulated time. Public so tests can drive it directly. */
    public Map<String, AlertReconciler.Plan> evaluate(Instant now) {
        SlaSnapshot snapshot = loader.load(now);
        Map<String, AlertReconciler.Plan> plans = new LinkedHashMap<>();
        for (SlaRule rule : rules) {
            plans.put(rule.id(), alertService.reconcile(rule.id(), rule.evaluate(snapshot), now));
        }
        Map<String, Integer> active = alertRepository.activeCountByRule();
        activeByRule.forEach((rule, gauge) -> gauge.set(active.getOrDefault(rule, 0)));
        lastEvaluatedAt = now;
        return plans;
    }

    public List<SlaRule> rules() {
        return rules;
    }

    public Instant lastEvaluatedAt() {
        return lastEvaluatedAt;
    }
}
