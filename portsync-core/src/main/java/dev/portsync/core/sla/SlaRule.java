package dev.portsync.core.sla;

import java.util.List;

/** A service-level rule: given the current terminal snapshot, which entities are in breach? */
public interface SlaRule {

    /** Stable identifier, also used for alert de-duplication and metric tags. */
    String id();

    /** Short human description including the configured threshold. */
    String description();

    List<Breach> evaluate(SlaSnapshot snapshot);
}
