package dev.portsync.core.ingest;

/**
 * Machine-readable reasons an event is dead-lettered. Stored in the DLQ header
 * {@code portsync-dlq-reason}, the dlq_events table and processing metrics.
 *
 * <p>Some reasons can be caused by ordering across topics rather than by a bad event: a crane move
 * can be consumed a moment before the BERTH_ASSIGNED it depends on, because they travel on different
 * topics. Those reasons are marked {@code mayResolve}: the listener retries the event for a short
 * grace period before dead-lettering it. The others are wrong whatever the timing.
 */
public enum RejectReason {
    MALFORMED_EVENT(false),
    INVALID_CHECK_DIGIT(false),
    UNKNOWN_EQUIPMENT(false),
    UNKNOWN_VESSEL(true),
    VESSEL_STATE_CONFLICT(true),
    BERTH_OCCUPIED(true),
    CRANE_FAULTED(true),
    CRANE_NOT_ASSIGNED(true),
    CRANE_UNAVAILABLE(true),
    CONTAINER_STATE_CONFLICT(true),
    CONTAINER_NOT_IN_YARD(true),
    SLOT_INVALID(false),
    STACK_FULL(true),
    UNKNOWN_TRUCK_VISIT(false),
    TRUCK_VISIT_CONFLICT(false),
    PROCESSING_FAILED(false);

    private final boolean mayResolve;

    RejectReason(boolean mayResolve) {
        this.mayResolve = mayResolve;
    }

    /** True if an event on another topic, not yet applied, could make this event valid. */
    public boolean mayResolve() {
        return mayResolve;
    }
}
