package dev.portsync.core.ingest.handler;

import dev.portsync.core.ingest.RejectReason;
import dev.portsync.core.ingest.RejectedEventException;
import dev.portsync.events.ContainerNumber;
import dev.portsync.events.MalformedEventException;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.TerminalLayout;

/** Small validation helpers shared by the handlers; each failure becomes a DLQ reason. */
final class Checks {

    private Checks() {
    }

    /** Returns the normalised container number or rejects with INVALID_CHECK_DIGIT. */
    static String container(String candidate) {
        ContainerNumber.Validation v = ContainerNumber.validate(candidate);
        if (!v.valid()) {
            throw new RejectedEventException(RejectReason.INVALID_CHECK_DIGIT,
                    "Container number '" + candidate + "' is invalid: " + v.reason());
        }
        return v.input();
    }

    static void crane(String craneId) {
        if (!TerminalLayout.isCrane(craneId)) {
            throw new RejectedEventException(RejectReason.UNKNOWN_EQUIPMENT, "Unknown quay crane '" + craneId + "'");
        }
    }

    static void berth(String berthId) {
        if (!TerminalLayout.isBerth(berthId)) {
            throw new RejectedEventException(RejectReason.UNKNOWN_EQUIPMENT, "Unknown berth '" + berthId + "'");
        }
    }

    static void lane(String lane) {
        if (!TerminalLayout.isLane(lane)) {
            throw new RejectedEventException(RejectReason.UNKNOWN_EQUIPMENT, "Unknown gate lane '" + lane + "'");
        }
    }

    static void present(Object value, String field) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new RejectedEventException(RejectReason.MALFORMED_EVENT, "Payload field '" + field + "' is required");
        }
    }

    /** Typed payload, turning a shape mismatch into a MALFORMED_EVENT rejection. */
    static <T> T payload(TerminalEvent event, Class<T> type) {
        try {
            return event.payloadAs(type);
        } catch (MalformedEventException e) {
            throw new RejectedEventException(RejectReason.MALFORMED_EVENT, e.getMessage());
        }
    }
}
