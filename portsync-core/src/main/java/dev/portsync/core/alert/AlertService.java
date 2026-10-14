package dev.portsync.core.alert;

import dev.portsync.core.api.ApiException;
import dev.portsync.core.clock.SimulatedClock;
import dev.portsync.core.sla.Breach;
import dev.portsync.core.stream.AfterCommit;
import dev.portsync.core.stream.LiveMessage;
import dev.portsync.core.stream.SseHub;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Applies reconciliation plans and the manual acknowledge / resolve transitions. */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alerts;
    private final SimulatedClock clock;
    private final SseHub hub;
    private final MeterRegistry registry;

    public AlertService(AlertRepository alerts, SimulatedClock clock, SseHub hub, MeterRegistry registry) {
        this.alerts = alerts;
        this.clock = clock;
        this.hub = hub;
        this.registry = registry;
    }

    /** Reconciles one rule's breaches with its active alerts. Returns the plan that was applied. */
    @Transactional
    public AlertReconciler.Plan reconcile(String rule, List<Breach> breaches, Instant now) {
        AlertReconciler.Plan plan = AlertReconciler.plan(alerts.findActiveByRule(rule), breaches);
        for (Breach b : plan.toOpen()) {
            if (alerts.open(b, now)) {
                log.warn("Alert opened: {} {} - {}", b.rule(), b.entityId(), b.message());
                Counter.builder("portsync.alerts.raised").description("Alerts opened, by rule")
                        .tag("rule", b.rule()).register(registry).increment();
                announce("OPENED", b.rule(), b.entityId(), b.message());
            }
        }
        for (AlertReconciler.Refresh r : plan.toRefresh()) {
            alerts.refresh(r.alert().id(), r.breach(), now);
        }
        for (Alert a : plan.toResolve()) {
            if (alerts.resolve(a.id(), now, "AUTO")) {
                log.info("Alert auto-resolved: {} {}", a.rule(), a.entityId());
                announce("RESOLVED", a.rule(), a.entityId(), "Condition cleared: " + a.message());
            }
        }
        return plan;
    }

    public List<Alert> list(Collection<AlertState> states, int limit) {
        return alerts.find(states, limit);
    }

    @Transactional
    public Alert acknowledge(long id) {
        Alert alert = existing(id);
        if (!alerts.acknowledge(id, clock.now())) {
            throw ApiException.conflict("ALERT_STATE_CONFLICT",
                    "Alert " + id + " is " + alert.state() + "; only OPEN alerts can be acknowledged");
        }
        announce("ACKNOWLEDGED", alert.rule(), alert.entityId(), alert.message());
        return existing(id);
    }

    /**
     * Manually resolves an alert. If the condition still holds, the next evaluation opens a new
     * alert: resolving cannot hide a real, ongoing breach.
     */
    @Transactional
    public Alert resolve(long id) {
        Alert alert = existing(id);
        if (!alerts.resolve(id, clock.now(), "MANUAL")) {
            throw ApiException.conflict("ALERT_STATE_CONFLICT", "Alert " + id + " is already RESOLVED");
        }
        announce("RESOLVED", alert.rule(), alert.entityId(), alert.message());
        return existing(id);
    }

    private void announce(String change, String rule, String entityId, String message) {
        AfterCommit.run(() -> hub.broadcast(new LiveMessage("alert", new AlertMessage(change, rule, entityId, message))));
    }

    private Alert existing(long id) {
        return alerts.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ALERT_NOT_FOUND", "No alert with id " + id));
    }

    public record AlertMessage(String change, String rule, String entityId, String message) {
    }
}
