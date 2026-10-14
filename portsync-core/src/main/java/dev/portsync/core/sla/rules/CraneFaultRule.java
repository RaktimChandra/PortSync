package dev.portsync.core.sla.rules;

import dev.portsync.core.sla.Breach;
import dev.portsync.core.sla.Severity;
import dev.portsync.core.sla.SlaRule;
import dev.portsync.core.sla.SlaSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** A crane must not stay faulted longer than N minutes. Critical: it stops a vessel operation. */
public class CraneFaultRule implements SlaRule {

    public static final String ID = "CRANE_FAULT_DURATION";

    private final Duration max;

    public CraneFaultRule(Duration max) {
        this.max = max;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Crane faulted longer than " + max.toMinutes() + " min";
    }

    @Override
    public List<Breach> evaluate(SlaSnapshot s) {
        List<Breach> breaches = new ArrayList<>();
        for (SlaSnapshot.Crane crane : s.cranes()) {
            if (!crane.faulted() || crane.faultedSince() == null) {
                continue;
            }
            Duration down = Duration.between(crane.faultedSince(), s.now());
            if (down.compareTo(max) > 0) {
                breaches.add(new Breach(ID, crane.id(), Severity.CRITICAL,
                        String.format("%s has been faulted (%s) for %s (maximum %d min)",
                                crane.id(), crane.faultCode(), Breach.describe(down), max.toMinutes()),
                        Breach.minutes(down), max.toMinutes()));
            }
        }
        return breaches;
    }
}
