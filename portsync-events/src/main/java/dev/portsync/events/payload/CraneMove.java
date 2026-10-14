package dev.portsync.events.payload;

/**
 * CRANE_MOVE: a quay crane completed one container move.
 * entityId = container number (so all crane moves of one container share a partition and stay in order).
 */
public record CraneMove(String craneId, String vesselId, MoveKind kind) {
}
