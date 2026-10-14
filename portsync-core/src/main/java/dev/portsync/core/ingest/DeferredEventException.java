package dev.portsync.core.ingest;

/**
 * Thrown when an event is not applicable <em>yet</em> and may become applicable once an event on
 * another topic is processed. The transaction rolls back completely (including the idempotency
 * claim) and the listener tries again after a short pause.
 */
public class DeferredEventException extends RuntimeException {

    private final RejectReason reason;

    public DeferredEventException(RejectedEventException cause) {
        super(cause.getMessage(), cause);
        this.reason = cause.reason();
    }

    public RejectReason reason() {
        return reason;
    }
}
