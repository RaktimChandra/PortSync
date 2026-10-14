package dev.portsync.core.sla.rules;

import dev.portsync.core.sla.Breach;
import dev.portsync.core.sla.Severity;
import dev.portsync.core.sla.SlaRule;
import dev.portsync.core.sla.SlaSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** A vessel must not wait for a berth longer than N minutes. */
public class BerthWaitRule implements SlaRule {

    public static final String ID = "VESSEL_BERTH_WAIT";

    private final Duration max;

    public BerthWaitRule(Duration max) {
        this.max = max;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Vessel waiting for a berth longer than " + max.toMinutes() + " min";
    }

    @Override
    public List<Breach> evaluate(SlaSnapshot s) {
        List<Breach> breaches = new ArrayList<>();
        for (SlaSnapshot.WaitingVessel vessel : s.waitingVessels()) {
            Duration waited = Duration.between(vessel.arrivedAt(), s.now());
            if (waited.compareTo(max) > 0) {
                breaches.add(new Breach(ID, vessel.id(), Severity.WARNING,
                        String.format("%s (%s) has waited %s for a berth (maximum %d min)",
                                vessel.name(), vessel.id(), Breach.describe(waited), max.toMinutes()),
                        Breach.minutes(waited), max.toMinutes()));
            }
        }
        return breaches;
    }
}
