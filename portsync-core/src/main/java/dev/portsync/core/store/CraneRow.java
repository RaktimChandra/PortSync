package dev.portsync.core.store;

import java.time.Instant;

/** A quay crane as stored. Status is derived: a fault overrides assignment. */
public record CraneRow(String id, String berthId, Instant assignedAt, boolean faulted, String faultCode,
                       Instant faultedSince, Instant lastHeartbeatAt) {

    public String status() {
        if (faulted) {
            return "FAULTED";
        }
        return berthId != null ? "WORKING" : "IDLE";
    }
}
