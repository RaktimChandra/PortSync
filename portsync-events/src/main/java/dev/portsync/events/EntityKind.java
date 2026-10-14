package dev.portsync.events;

/**
 * The kind of business entity an event is about. The pair (kind, entityId) is the unit of
 * ordering: Kafka keeps one entity's events in order because they share a partition key, and the
 * consumer's out-of-order rule compares timestamps per (kind, entityId).
 */
public enum EntityKind {
    CONTAINER,
    CRANE,
    VESSEL
}
