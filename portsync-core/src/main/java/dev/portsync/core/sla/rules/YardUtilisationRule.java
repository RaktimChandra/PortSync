package dev.portsync.core.sla.rules;

import dev.portsync.core.sla.Breach;
import dev.portsync.core.sla.Severity;
import dev.portsync.core.sla.SlaRule;
import dev.portsync.core.sla.SlaSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * A yard block must stay at or below a utilisation ratio (85% by default): above that, finding
 * slots forces deep stacks and rehandles. Critical from a second, higher ratio.
 */
public class YardUtilisationRule implements SlaRule {

    public static final String ID = "YARD_BLOCK_UTILISATION";

    private final double maxRatio;
    private final double criticalRatio;

    public YardUtilisationRule(double maxRatio, double criticalRatio) {
        this.maxRatio = maxRatio;
        this.criticalRatio = criticalRatio;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Yard block above " + Math.round(maxRatio * 100) + "% full";
    }

    @Override
    public List<Breach> evaluate(SlaSnapshot s) {
        List<Breach> breaches = new ArrayList<>();
        for (SlaSnapshot.Block block : s.blocks()) {
            double ratio = block.ratio();
            if (ratio > maxRatio) {
                Severity severity = ratio >= criticalRatio ? Severity.CRITICAL : Severity.WARNING;
                breaches.add(new Breach(ID, block.block(), severity,
                        String.format("Block %s is %.1f%% full (%d/%d slots, limit %.0f%%)",
                                block.block(), ratio * 100, block.used(), block.capacity(), maxRatio * 100),
                        Math.round(ratio * 1000) / 10.0, Math.round(maxRatio * 1000) / 10.0));
            }
        }
        return breaches;
    }
}
