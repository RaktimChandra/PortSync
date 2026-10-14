package dev.portsync.core.ingest;

import dev.portsync.events.EventType;
import dev.portsync.events.TerminalEvent;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Outcome of processing one event, published to metrics and the live stream after commit. */
public record ProcessingResult(
        UUID eventId,
        EventType type,
        String entityId,
        Instant occurredAt,
        Outcome outcome,
        String reason,
        String detail,
        Set<Scope> scopes,
        String topic,
        int partition,
        long offset) {

    public static ProcessingResult of(TerminalEvent e, RecordMeta meta, Outcome outcome, String reason, String detail,
                                      Set<Scope> scopes) {
        return new ProcessingResult(e.eventId(), e.type(), e.entityId(), e.occurredAt(), outcome, reason, detail,
                Set.copyOf(scopes), meta.topic(), meta.partition(), meta.offset());
    }
}
