package dev.portsync.core.ingest;

/**
 * Thrown by a handler when an event is invalid or impossible given the current terminal state.
 * It is a normal business outcome, not a bug: the processor rolls back to its savepoint, records
 * the rejection and dead-letters the event.
 */
public class RejectedEventException extends RuntimeException {

    private final RejectReason reason;

    public RejectedEventException(RejectReason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
