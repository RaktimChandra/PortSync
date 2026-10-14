package dev.portsync.core.alert;

import java.time.Instant;

/** An SLA alert as stored. Times are simulated time. */
public record Alert(long id, String rule, String entityId, String severity, AlertState state, String message,
                    Double observedValue, Double threshold, Instant openedAt, Instant lastSeenAt,
                    Instant acknowledgedAt, Instant resolvedAt, String resolution) {

    /** De-duplication key: one active alert per rule and entity. */
    public String key() {
        return AlertReconciler.key(rule, entityId);
    }
}
