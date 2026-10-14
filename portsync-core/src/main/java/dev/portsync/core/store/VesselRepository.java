package dev.portsync.core.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class VesselRepository {

    private static final String COLUMNS = "id, name, voyage, status, berth_id, arrived_at, berthed_at, departed_at, "
            + "discharge_planned, load_planned, discharged, loaded";

    private static final RowMapper<VesselRow> MAPPER = (rs, n) -> new VesselRow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("voyage"),
            rs.getString("status"),
            rs.getString("berth_id"),
            Sql.instant(rs, "arrived_at"),
            Sql.instant(rs, "berthed_at"),
            Sql.instant(rs, "departed_at"),
            rs.getInt("discharge_planned"),
            rs.getInt("load_planned"),
            rs.getInt("discharged"),
            rs.getInt("loaded"));

    private final JdbcClient jdbc;

    public VesselRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VesselRow> lock(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vessels WHERE id = :id FOR UPDATE")
                .param("id", id).query(MAPPER).optional();
    }

    public Optional<VesselRow> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vessels WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public List<VesselRow> findByStatus(String status) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vessels WHERE status = :status ORDER BY arrived_at, id")
                .param("status", status).query(MAPPER).list();
    }

    /** Vessels that are waiting or berthed, plus the most recent departures. */
    public List<VesselRow> findRecent(int departedLimit) {
        return jdbc.sql("""
                        (SELECT %1$s FROM vessels WHERE status <> 'DEPARTED')
                        UNION ALL
                        (SELECT %1$s FROM vessels WHERE status = 'DEPARTED' ORDER BY departed_at DESC LIMIT :limit)
                        ORDER BY arrived_at DESC, id
                        """.formatted(COLUMNS))
                .param("limit", departedLimit).query(MAPPER).list();
    }

    public void insertArrived(String id, String name, String voyage, Instant arrivedAt, int dischargePlanned, int loadPlanned) {
        jdbc.sql("""
                        INSERT INTO vessels (id, name, voyage, status, arrived_at, discharge_planned, load_planned)
                        VALUES (:id, :name, :voyage, 'WAITING', :at, :discharge, :load)
                        """)
                .param("id", id).param("name", name).param("voyage", voyage).param("at", Sql.ts(arrivedAt))
                .param("discharge", dischargePlanned).param("load", loadPlanned)
                .update();
    }

    public void berth(String id, String berthId, Instant at) {
        jdbc.sql("UPDATE vessels SET status = 'AT_BERTH', berth_id = :berth, berthed_at = :at WHERE id = :id")
                .param("berth", berthId).param("at", Sql.ts(at)).param("id", id).update();
    }

    public void depart(String id, Instant at) {
        jdbc.sql("UPDATE vessels SET status = 'DEPARTED', departed_at = :at WHERE id = :id")
                .param("at", Sql.ts(at)).param("id", id).update();
    }

    public void countDischarge(String id) {
        jdbc.sql("UPDATE vessels SET discharged = discharged + 1 WHERE id = :id").param("id", id).update();
    }

    public void countLoad(String id) {
        jdbc.sql("UPDATE vessels SET loaded = loaded + 1 WHERE id = :id").param("id", id).update();
    }

    public long countByStatus(String status) {
        return jdbc.sql("SELECT count(*) FROM vessels WHERE status = :status")
                .param("status", status).query(Long.class).single();
    }
}
