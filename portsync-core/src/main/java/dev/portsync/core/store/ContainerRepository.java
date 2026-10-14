package dev.portsync.core.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class ContainerRepository {

    private static final RowMapper<ContainerRow> MAPPER = (rs, n) -> new ContainerRow(
            rs.getString("number"), rs.getString("status"), rs.getString("vessel_id"), Sql.instant(rs, "last_event_at"));

    private final JdbcClient jdbc;

    public ContainerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ContainerRow> lock(String number) {
        return jdbc.sql("SELECT number, status, vessel_id, last_event_at FROM containers WHERE number = :n FOR UPDATE")
                .param("n", number).query(MAPPER).optional();
    }

    public Optional<ContainerRow> findByNumber(String number) {
        return jdbc.sql("SELECT number, status, vessel_id, last_event_at FROM containers WHERE number = :n")
                .param("n", number).query(MAPPER).optional();
    }

    /** Creates the container or overwrites a finished lifecycle (a box can visit the terminal again). */
    public void upsert(String number, String status, String vesselId, Instant at) {
        jdbc.sql("""
                        INSERT INTO containers (number, status, vessel_id, last_event_at)
                        VALUES (:n, :status, :vessel, :at)
                        ON CONFLICT (number) DO UPDATE
                        SET status = EXCLUDED.status, vessel_id = EXCLUDED.vessel_id,
                            last_event_at = EXCLUDED.last_event_at, updated_at = now()
                        """)
                .param("n", number).param("status", status).param("vessel", vesselId).param("at", Sql.ts(at))
                .update();
    }

    public void setStatus(String number, String status, Instant at) {
        jdbc.sql("UPDATE containers SET status = :status, last_event_at = :at, updated_at = now() WHERE number = :n")
                .param("status", status).param("at", Sql.ts(at)).param("n", number).update();
    }

    public void setStatusAndVessel(String number, String status, String vesselId, Instant at) {
        jdbc.sql("""
                        UPDATE containers SET status = :status, vessel_id = :vessel, last_event_at = :at, updated_at = now()
                        WHERE number = :n
                        """)
                .param("status", status).param("vessel", vesselId).param("at", Sql.ts(at)).param("n", number).update();
    }
}
