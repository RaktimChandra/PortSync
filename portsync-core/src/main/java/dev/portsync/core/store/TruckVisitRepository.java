package dev.portsync.core.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class TruckVisitRepository {

    private static final String COLUMNS = "id, container_number, direction, lane, truck_plate, status, gate_in_at, gate_out_at";

    private static final RowMapper<TruckVisitRow> MAPPER = (rs, n) -> new TruckVisitRow(
            rs.getString("id"), rs.getString("container_number"), rs.getString("direction"), rs.getString("lane"),
            rs.getString("truck_plate"), rs.getString("status"), Sql.instant(rs, "gate_in_at"),
            Sql.instant(rs, "gate_out_at"));

    private final JdbcClient jdbc;

    public TruckVisitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TruckVisitRow> lock(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM truck_visits WHERE id = :id FOR UPDATE")
                .param("id", id).query(MAPPER).optional();
    }

    public boolean hasOpenPickup(String containerNumber) {
        return jdbc.sql("""
                        SELECT count(*) FROM truck_visits
                        WHERE container_number = :n AND status = 'OPEN' AND direction = 'PICK_UP'
                        """)
                .param("n", containerNumber).query(Long.class).single() > 0;
    }

    public void open(String id, String containerNumber, String direction, String lane, String plate, Instant gateInAt) {
        jdbc.sql("""
                        INSERT INTO truck_visits (id, container_number, direction, lane, truck_plate, status, gate_in_at)
                        VALUES (:id, :n, :direction, :lane, :plate, 'OPEN', :at)
                        """)
                .param("id", id).param("n", containerNumber).param("direction", direction).param("lane", lane)
                .param("plate", plate).param("at", Sql.ts(gateInAt))
                .update();
    }

    public void close(String id, Instant gateOutAt) {
        jdbc.sql("UPDATE truck_visits SET status = 'CLOSED', gate_out_at = :at WHERE id = :id")
                .param("at", Sql.ts(gateOutAt)).param("id", id).update();
    }

    public List<TruckVisitRow> findOpen() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM truck_visits WHERE status = 'OPEN' ORDER BY gate_in_at")
                .query(MAPPER).list();
    }

    /** Average gate-in to gate-out minutes for visits that left in (from, to]; null when none did. */
    public Double averageTurnMinutes(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT avg(extract(epoch FROM (gate_out_at - gate_in_at)) / 60.0) AS minutes
                        FROM truck_visits
                        WHERE status = 'CLOSED' AND gate_out_at > :from AND gate_out_at <= :to
                        """)
                .param("from", Sql.ts(from)).param("to", Sql.ts(to))
                .query((rs, n) -> Sql.nullableDouble(rs, "minutes"))
                .optional().orElse(null);
    }

    public long countOpen() {
        return jdbc.sql("SELECT count(*) FROM truck_visits WHERE status = 'OPEN'").query(Long.class).single();
    }
}
