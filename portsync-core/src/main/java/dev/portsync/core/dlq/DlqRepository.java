package dev.portsync.core.dlq;

import dev.portsync.core.store.Sql;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class DlqRepository {

    private static final String COLUMNS = "id, event_id, event_type, entity_id, reason, detail, raw_value, "
            + "source_topic, source_partition, source_offset, dlq_partition, dlq_offset, dead_lettered_at";

    private static final RowMapper<DlqEntry> MAPPER = (rs, n) -> new DlqEntry(
            rs.getLong("id"),
            rs.getObject("event_id", UUID.class),
            rs.getString("event_type"),
            rs.getString("entity_id"),
            rs.getString("reason"),
            rs.getString("detail"),
            rs.getString("raw_value"),
            rs.getString("source_topic"),
            rs.getInt("source_partition"),
            rs.getLong("source_offset"),
            rs.getInt("dlq_partition"),
            rs.getLong("dlq_offset"),
            Sql.instant(rs, "dead_lettered_at"));

    private final JdbcClient jdbc;

    public DlqRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if stored, false if this source record was already materialised */
    public boolean insert(DlqEntry e) {
        return jdbc.sql("""
                        INSERT INTO dlq_events (event_id, event_type, entity_id, reason, detail, raw_value,
                                                source_topic, source_partition, source_offset, dlq_partition, dlq_offset)
                        VALUES (:eventId, :type, :entity, :reason, :detail, :raw, :topic, :partition, :offset,
                                :dlqPartition, :dlqOffset)
                        ON CONFLICT ON CONSTRAINT ux_dlq_source DO NOTHING
                        """)
                .param("eventId", e.eventId())
                .param("type", e.eventType())
                .param("entity", e.entityId())
                .param("reason", e.reason())
                .param("detail", e.detail())
                .param("raw", e.rawValue())
                .param("topic", e.sourceTopic())
                .param("partition", e.sourcePartition())
                .param("offset", e.sourceOffset())
                .param("dlqPartition", e.dlqPartition())
                .param("dlqOffset", e.dlqOffset())
                .update() == 1;
    }

    public List<DlqEntry> recent(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dlq_events ORDER BY dead_lettered_at DESC, id DESC LIMIT :limit")
                .param("limit", limit).query(MAPPER).list();
    }

    public List<DlqEntry> forEntity(String entityId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dlq_events WHERE entity_id = :entity ORDER BY dead_lettered_at, id")
                .param("entity", entityId).query(MAPPER).list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM dlq_events").query(Long.class).single();
    }

    public record DlqEntry(Long id, UUID eventId, String eventType, String entityId, String reason, String detail,
                           String rawValue, String sourceTopic, int sourcePartition, long sourceOffset,
                           int dlqPartition, long dlqOffset, Instant deadLetteredAt) {
    }
}
