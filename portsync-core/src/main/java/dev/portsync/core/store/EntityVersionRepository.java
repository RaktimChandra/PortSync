package dev.portsync.core.store;

import dev.portsync.events.EntityKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Tracks the {@code occurredAt} of the newest event applied to each entity. This is what the
 * out-of-order rule compares against.
 */
@Repository
public class EntityVersionRepository {

    private final JdbcClient jdbc;

    public EntityVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Reads (and row-locks, if present) the last applied event time of an entity. */
    public Optional<Instant> lastOccurredAt(EntityKind kind, String entityId) {
        return jdbc.sql("""
                        SELECT last_occurred_at FROM entity_versions
                        WHERE entity_kind = :kind AND entity_id = :id
                        FOR UPDATE
                        """)
                .param("kind", kind.name())
                .param("id", entityId)
                .query((rs, n) -> Sql.instant(rs, "last_occurred_at"))
                .optional();
    }

    /** Records that an event was applied. The stored time only ever moves forward. */
    public void advance(EntityKind kind, String entityId, Instant occurredAt, UUID eventId) {
        jdbc.sql("""
                        INSERT INTO entity_versions (entity_kind, entity_id, last_occurred_at, last_event_id)
                        VALUES (:kind, :id, :at, :eventId)
                        ON CONFLICT (entity_kind, entity_id) DO UPDATE
                        SET last_event_id    = CASE WHEN EXCLUDED.last_occurred_at >= entity_versions.last_occurred_at
                                                    THEN EXCLUDED.last_event_id ELSE entity_versions.last_event_id END,
                            last_occurred_at = GREATEST(entity_versions.last_occurred_at, EXCLUDED.last_occurred_at)
                        """)
                .param("kind", kind.name())
                .param("id", entityId)
                .param("at", Sql.ts(occurredAt))
                .param("eventId", eventId)
                .update();
    }
}
