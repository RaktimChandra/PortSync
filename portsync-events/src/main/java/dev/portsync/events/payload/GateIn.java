package dev.portsync.events.payload;

/**
 * GATE_IN: a truck passed the in-gate.
 * entityId = the container being dropped off or picked up.
 */
public record GateIn(String truckVisitId, String lane, GateDirection direction, String truckPlate) {
}
