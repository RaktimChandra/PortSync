package dev.portsync.core.ops;

import dev.portsync.core.store.YardRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Continuously audits the yard invariants in production, not just in tests, and exports the number
 * of violations as {@code portsync_yard_invariant_violations}. Anything above zero is a bug worth
 * an alert in Grafana.
 */
@Component
public class InvariantMonitor {

    private static final Logger log = LoggerFactory.getLogger(InvariantMonitor.class);

    private final YardRepository yard;
    private final AtomicInteger violations = new AtomicInteger();

    public InvariantMonitor(YardRepository yard, MeterRegistry registry) {
        this.yard = yard;
        Gauge.builder("portsync.yard.invariant.violations", violations, AtomicInteger::get)
                .description("Yard invariant violations found by the last audit (should be 0)")
                .register(registry);
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void audit() {
        try {
            check();
        } catch (RuntimeException e) {
            log.warn("Invariant audit failed: {}", e.getMessage());
        }
    }

    public List<YardRepository.InvariantViolation> check() {
        List<YardRepository.InvariantViolation> found = yard.invariantViolations();
        violations.set(found.size());
        if (!found.isEmpty()) {
            log.error("Yard invariant violations: {}", found);
        }
        return found;
    }
}
