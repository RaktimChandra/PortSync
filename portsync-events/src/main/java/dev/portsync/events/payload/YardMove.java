package dev.portsync.events.payload;

import dev.portsync.events.yard.SlotRef;

/**
 * YARD_MOVE: a container was put down in a yard slot (from the quay, the gate or another slot).
 * entityId = container number.
 */
public record YardMove(String block, int row, int bay, int tier) {

    public static YardMove to(SlotRef slot) {
        return new YardMove(slot.block(), slot.row(), slot.bay(), slot.tier());
    }

    public SlotRef slot() {
        return new SlotRef(block, row, bay, tier);
    }
}
