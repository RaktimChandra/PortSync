package dev.portsync.events.payload;

/** Direction of a quay crane move. */
public enum MoveKind {
    /** Vessel to quay (import). */
    DISCHARGE,
    /** Quay to vessel (export). */
    LOAD
}
