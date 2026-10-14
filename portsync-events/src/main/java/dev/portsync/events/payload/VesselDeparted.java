package dev.portsync.events.payload;

/**
 * VESSEL_DEPARTED: the vessel left the berth; its berth and cranes become free.
 * entityId = vessel visit id.
 */
public record VesselDeparted(String berthId) {
}
