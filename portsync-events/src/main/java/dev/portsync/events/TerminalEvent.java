package dev.portsync.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The envelope every terminal event travels in. The JSON Schema for it lives in
 * {@code src/main/resources/schema/terminal-event.schema.json}.
 *
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "eventId": "6f1c...",            // unique per event; the idempotency key
 *   "type": "CRANE_MOVE",
 *   "occurredAt": "2026-10-14T08:15:00Z",   // SIMULATED time the event happened
 *   "entityId": "CSQU3054383",       // also the Kafka record key
 *   "source": "simulator",
 *   "payload": { "craneId": "QC01", "vesselId": "V-0001", "kind": "DISCHARGE" }
 * }
 * }</pre>
 */
public record TerminalEvent(
        int schemaVersion,
        UUID eventId,
        EventType type,
        Instant occurredAt,
        String entityId,
        String source,
        JsonNode payload) {

    public static final int SCHEMA_VERSION = 1;

    /** Creates a new event with a fresh random eventId. */
    public static TerminalEvent of(EventType type, Instant occurredAt, String entityId, String source, Object payload) {
        return new TerminalEvent(SCHEMA_VERSION, UUID.randomUUID(), type, occurredAt, entityId, source,
                EventJson.mapper().valueToTree(payload));
    }

    /** Kafka topic this event is published to. */
    public String topic() {
        return type.topic();
    }

    /**
     * Kafka record key. Using the entity id means all events of one container, crane or vessel
     * hash to the same partition and are therefore delivered in the order they were produced.
     */
    public String partitionKey() {
        return entityId;
    }

    /** Converts the payload to its typed record. */
    public <T> T payloadAs(Class<T> type) {
        try {
            return EventJson.mapper().treeToValue(payload, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new MalformedEventException("Payload of " + this.type + " is not a valid " + type.getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    /** Same event content with a different occurrence time (used for out-of-order fault injection). */
    public TerminalEvent withOccurredAt(Instant newOccurredAt) {
        return new TerminalEvent(schemaVersion, eventId, type, newOccurredAt, entityId, source, payload);
    }

    /** Same content with a new eventId (a genuinely new event, not a duplicate). */
    public TerminalEvent withNewEventId() {
        return new TerminalEvent(schemaVersion, UUID.randomUUID(), type, occurredAt, entityId, source, payload);
    }

    /** Throws {@link MalformedEventException} if any mandatory envelope field is missing. */
    public void requireCompleteEnvelope() {
        if (eventId == null) {
            throw new MalformedEventException("Missing eventId");
        }
        if (type == null) {
            throw new MalformedEventException("Missing or unknown type");
        }
        if (occurredAt == null) {
            throw new MalformedEventException("Missing occurredAt");
        }
        if (entityId == null || entityId.isBlank()) {
            throw new MalformedEventException("Missing entityId");
        }
        if (payload == null || !payload.isObject()) {
            throw new MalformedEventException("Missing payload object");
        }
    }
}
