package dev.portsync.core.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Repository
public class CraneMoveRepository {

    private final JdbcClient jdbc;

    public CraneMoveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID eventId, String craneId, String vesselId, String container, String kind, Instant at) {
        jdbc.sql("""
                        INSERT INTO crane_moves (event_id, crane_id, vessel_id, container_number, kind, occurred_at)
                        VALUES (:eventId, :crane, :vessel, :container, :kind, :at)
                        """)
                .param("eventId", eventId).param("crane", craneId).param("vessel", vesselId)
                .param("container", container).param("kind", kind).param("at", Sql.ts(at))
                .update();
    }

    /** Moves per crane with occurredAt in (from, to]. Cranes without moves are absent from the map. */
    public Map<String, Integer> countByCraneBetween(Instant from, Instant to) {
        Map<String, Integer> result = new HashMap<>();
        jdbc.sql("""
                        SELECT crane_id, count(*) AS moves FROM crane_moves
                        WHERE occurred_at > :from AND occurred_at <= :to
                        GROUP BY crane_id
                        """)
                .param("from", Sql.ts(from)).param("to", Sql.ts(to))
                .query(rs -> {
                    result.put(rs.getString("crane_id"), rs.getInt("moves"));
                });
        return result;
    }

    public long countBetween(Instant from, Instant to) {
        return jdbc.sql("SELECT count(*) FROM crane_moves WHERE occurred_at > :from AND occurred_at <= :to")
                .param("from", Sql.ts(from)).param("to", Sql.ts(to)).query(Long.class).single();
    }
}
