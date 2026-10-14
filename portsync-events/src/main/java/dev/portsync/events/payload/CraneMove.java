package dev.portsync.events.payload;

/**
 * CRANE_MOVE: a quay crane completed one container move.
 * entityId = container number (so a container's events stay ordered).
 */
public record CraneMove(String craneId, String vesselId, MoveKind kind) {
}
