package dev.portsync.core.ingest;

/**
 * Machine-readable reasons an event is dead-lettered. Stored in the DLQ header
 * {@code portsync-dlq-reason}, the dlq_events table and processing metrics.
 */
public enum RejectReason {
    MALFORMED_EVENT,
    INVALID_CHECK_DIGIT,
    UNKNOWN_EQUIPMENT,
    UNKNOWN_VESSEL,
    VESSEL_STATE_CONFLICT,
    BERTH_OCCUPIED,
    CRANE_FAULTED,
    CRANE_NOT_ASSIGNED,
    CRANE_UNAVAILABLE,
    CONTAINER_STATE_CONFLICT,
    CONTAINER_NOT_IN_YARD,
    SLOT_INVALID,
    STACK_FULL,
    UNKNOWN_TRUCK_VISIT,
    TRUCK_VISIT_CONFLICT,
    PROCESSING_FAILED
}
