package dev.portsync.core.ingest;

/** What happened to an event. */
public enum Outcome {
    /** State was changed. */
    APPLIED,
    /** Older than the entity's last applied event; recorded but ignored (out-of-order rule). */
    IGNORED_STALE,
    /** Invalid or impossible; state untouched, event sent to terminal.dlq with a reason. */
    REJECTED,
    /** eventId already processed; nothing changed (not stored again). */
    DUPLICATE
}
