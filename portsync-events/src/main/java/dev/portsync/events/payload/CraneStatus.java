package dev.portsync.events.payload;

/**
 * CRANE_STATUS: heartbeat or fault/recovery signal from a quay crane.
 * entityId = crane id.
 *
 * @param signal    heartbeat, fault or recovered
 * @param faultCode machine fault code for FAULT signals, otherwise null
 */
public record CraneStatus(CraneSignal signal, String faultCode) {
}
