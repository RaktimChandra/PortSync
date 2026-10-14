package dev.portsync.events.payload;

/**
 * GATE_OUT: the truck of a visit left through the out-gate. For a pick-up visit the container
 * leaves with it and its yard slot is freed; for a drop-off the container stays in the terminal.
 * entityId = the container of the visit.
 */
public record GateOut(String truckVisitId, String lane) {
}
