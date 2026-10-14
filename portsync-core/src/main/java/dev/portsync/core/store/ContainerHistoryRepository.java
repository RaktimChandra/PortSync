package dev.portsync.core.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ContainerHistoryRepository {

    private final JdbcClient jdbc;

    public ContainerHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record HistoryRow(UUID eventId, String eventType, Instant occurredAt, String description) {
    }

    public void add(String number, UUID eventId, String eventType, Instant occurredAt, String description) {
        jdbc.sql("""
                        INSERT INTO container_history (container_number, event_id, event_type, occurred_at, description)
                        VALUES (:n, :eventId, :type, :at, :description)
                        """)
                .param("n", number).param("eventId", eventId).param("type", eventType)
                .param("at", Sql.ts(occurredAt)).param("description", description)
                .update();
    }

    public List<HistoryRow> findByContainer(String number) {
        return jdbc.sql("""
                        SELECT event_id, event_type, occurred_at, description FROM container_history
                        WHERE container_number = :n ORDER BY occurred_at, id
                        """)
                .param("n", number)
                .query((rs, i) -> new HistoryRow(rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                        Sql.instant(rs, "occurred_at"), rs.getString("description")))
                .list();
    }
}
