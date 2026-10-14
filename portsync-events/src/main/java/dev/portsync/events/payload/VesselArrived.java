package dev.portsync.events.payload;

/**
 * VESSEL_ARRIVED: a vessel visit reached port limits and joins the berth queue.
 * entityId = vessel visit id.
 *
 * @param vesselName      display name of the ship
 * @param voyage          voyage reference
 * @param dischargeCount  containers planned to be discharged
 * @param loadCount       containers planned to be loaded
 */
public record VesselArrived(String vesselName, String voyage, int dischargeCount, int loadCount) {
}
