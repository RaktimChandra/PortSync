package dev.portsync.core.store;

import dev.portsync.core.ingest.Outcome;
import dev.portsync.core.ingest.RecordMeta;
import dev.portsync.events.TerminalEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The idempotency ledger. {@link #claim} inserts the eventId with {@code ON CONFLICT DO NOTHING}:
 * if the insert affects zero rows the event was already processed (or is being processed by a
 * transaction that will commit first) and must be skipped. Because the claim and the state change
 * share one transaction, a crash between them rolls both back and the redelivered record is
 * processed exactly once.
 */
@Repository
public class ProcessedEventRepository {

    private final JdbcClient jdbc;

    public ProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this eventId was not seen before and is now claimed by the current transaction */
    public boolean claim(TerminalEvent event, RecordMeta meta) {
        int inserted = jdbc.sql("""
                        INSERT INTO processed_events
                            (event_id, event_type, entity_id, occurred_at, outcome, source_topic, source_partition, source_offset)
                        VALUES (:id, :type, :entity, :occurredAt, 'APPLIED', :topic, :partition, :offset)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("id", event.eventId())
                .param("type", event.type().name())
                .param("entity", truncate(event.entityId(), 64))
                .param("occurredAt", Sql.ts(event.occurredAt()))
                .param("topic", meta.topic())
                .param("partition", meta.partition())
                .param("offset", meta.offset())
                .update();
        return inserted == 1;
    }

    public void finish(UUID eventId, Outcome outcome, String reason, String detail) {
        jdbc.sql("UPDATE processed_events SET outcome = :outcome, reason = :reason, detail = :detail WHERE event_id = :id")
                .param("outcome", outcome.name())
                .param("reason", reason)
                .param("detail", detail)
                .param("id", eventId)
                .update();
    }

    public List<ProcessedEventRow> recent(int limit) {
        return jdbc.sql("""
                        SELECT event_id, event_type, entity_id, occurred_at, outcome, reason, detail,
                               source_topic, source_partition, source_offset, processed_at
                        FROM processed_events
                        ORDER BY processed_at DESC, event_id
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new ProcessedEventRow(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("entity_id"),
                        Sql.instant(rs, "occurred_at"),
                        rs.getString("outcome"),
                        rs.getString("reason"),
                        rs.getString("detail"),
                        rs.getString("source_topic"),
                        Sql.nullableInt(rs, "source_partition"),
                        Sql.nullableLong(rs, "source_offset"),
                        Sql.instant(rs, "processed_at")))
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM processed_events").query(Long.class).single();
    }

    public boolean exists(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id")
                .param("id", eventId).query(Long.class).single() > 0;
    }

    /** Deletes ledger rows older than the cutoff (wall-clock). Returns rows removed. */
    public int purgeProcessedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM processed_events WHERE processed_at < :cutoff")
                .param("cutoff", Sql.ts(cutoff))
                .update();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record ProcessedEventRow(UUID eventId, String type, String entityId, Instant occurredAt, String outcome,
                                    String reason, String detail, String topic, Integer partition, Long offset,
                                    Instant processedAt) {
    }
}
