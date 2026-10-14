package dev.portsync.core.sla.rules;

import dev.portsync.core.sla.Breach;
import dev.portsync.core.sla.Severity;
import dev.portsync.core.sla.SlaRule;
import dev.portsync.core.sla.SlaSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A truck must get from gate-in to gate-out within N minutes. Evaluated on trucks still inside,
 * so the alert fires while there is still time to act; it auto-resolves when the truck leaves.
 */
public class TruckTurnTimeRule implements SlaRule {

    public static final String ID = "TRUCK_TURN_TIME";

    private final Duration max;

    public TruckTurnTimeRule(Duration max) {
        this.max = max;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Truck turn time above " + max.toMinutes() + " min";
    }

    @Override
    public List<Breach> evaluate(SlaSnapshot s) {
        List<Breach> breaches = new ArrayList<>();
        for (SlaSnapshot.OpenTruckVisit visit : s.openTruckVisits()) {
            Duration inside = Duration.between(visit.gateInAt(), s.now());
            if (inside.compareTo(max) > 0) {
                breaches.add(new Breach(ID, visit.id(), Severity.WARNING,
                        String.format("Truck visit %s (%s %s, lane %s) inside for %s (maximum %d min)",
                                visit.id(), visit.direction(), visit.containerNumber(), visit.lane(),
                                Breach.describe(inside), max.toMinutes()),
                        Breach.minutes(inside), max.toMinutes()));
            }
        }
        return breaches;
    }
}
