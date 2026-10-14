package dev.portsync.core.sla.rules;

import dev.portsync.core.sla.Breach;
import dev.portsync.core.sla.Severity;
import dev.portsync.core.sla.SlaRule;
import dev.portsync.core.sla.SlaSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A working crane must average at least N moves per hour over a rolling window.
 *
 * <p>Only cranes that are assigned to a berth, are not faulted, and have been assigned for at
 * least one full window are evaluated: a crane that started 10 minutes ago has not had a fair
 * chance, and a faulted crane is already covered by {@link CraneFaultRule} (one root cause should
 * not page twice).
 */
public class CraneProductivityRule implements SlaRule {

    public static final String ID = "CRANE_LOW_PRODUCTIVITY";

    private final int minMovesPerHour;
    private final Duration window;

    public CraneProductivityRule(int minMovesPerHour, Duration window) {
        this.minMovesPerHour = minMovesPerHour;
        this.window = window;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Crane below " + minMovesPerHour + " moves/h over " + window.toMinutes() + " min";
    }

    @Override
    public List<Breach> evaluate(SlaSnapshot s) {
        List<Breach> breaches = new ArrayList<>();
        double windowHours = window.toMillis() / 3_600_000.0;
        for (SlaSnapshot.Crane crane : s.cranes()) {
            if (crane.berthId() == null || crane.faulted() || crane.assignedAt() == null) {
                continue;
            }
            if (crane.assignedAt().isAfter(s.now().minus(window))) {
                continue;
            }
            int moves = s.movesInWindow().getOrDefault(crane.id(), 0);
            double perHour = moves / windowHours;
            if (perHour < minMovesPerHour) {
                breaches.add(new Breach(ID, crane.id(), Severity.WARNING,
                        String.format("%s at berth %s averaged %.1f moves/h over the last %d min (minimum %d)",
                                crane.id(), crane.berthId(), perHour, window.toMinutes(), minMovesPerHour),
                        round(perHour), minMovesPerHour));
            }
        }
        return breaches;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
