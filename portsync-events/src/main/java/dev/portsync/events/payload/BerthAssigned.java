package dev.portsync.events.payload;

import java.util.List;

/**
 * BERTH_ASSIGNED: the vessel is moored at a berth and quay cranes are allocated to it.
 * entityId = vessel visit id.
 */
public record BerthAssigned(String berthId, List<String> craneIds) {

    public BerthAssigned {
        craneIds = craneIds == null ? List.of() : List.copyOf(craneIds);
    }
}
