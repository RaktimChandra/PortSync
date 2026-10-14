package dev.portsync.core.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class CraneRepository {

    private static final String COLUMNS =
            "id, berth_id, assigned_at, faulted, fault_code, faulted_since, last_heartbeat_at";

    private static final RowMapper<CraneRow> MAPPER = (rs, n) -> new CraneRow(
            rs.getString("id"),
            rs.getString("berth_id"),
            Sql.instant(rs, "assigned_at"),
            rs.getBoolean("faulted"),
            rs.getString("fault_code"),
            Sql.instant(rs, "faulted_since"),
            Sql.instant(rs, "last_heartbeat_at"));

    private final JdbcClient jdbc;

    public CraneRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CraneRow> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM cranes ORDER BY id").query(MAPPER).list();
    }

    public Optional<CraneRow> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM cranes WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    /** Row-locks the crane for the rest of the transaction. */
    public Optional<CraneRow> lock(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM cranes WHERE id = :id FOR UPDATE")
                .param("id", id).query(MAPPER).optional();
    }

    /** Locks several cranes in id order (a fixed order avoids deadlocks between transactions). */
    public List<CraneRow> lockAll(Collection<String> ids) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM cranes WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", ids).query(MAPPER).list();
    }

    public void assign(Collection<String> ids, String berthId, Instant at) {
        jdbc.sql("UPDATE cranes SET berth_id = :berth, assigned_at = :at, updated_at = now() WHERE id IN (:ids)")
                .param("berth", berthId).param("at", Sql.ts(at)).param("ids", ids).update();
    }

    public void releaseBerth(String berthId) {
        jdbc.sql("UPDATE cranes SET berth_id = NULL, assigned_at = NULL, updated_at = now() WHERE berth_id = :berth")
                .param("berth", berthId).update();
    }

    public void fault(String id, String faultCode, Instant since) {
        jdbc.sql("""
                        UPDATE cranes SET faulted = TRUE, fault_code = :code, faulted_since = :since, updated_at = now()
                        WHERE id = :id
                        """)
                .param("code", faultCode).param("since", Sql.ts(since)).param("id", id).update();
    }

    public void recover(String id) {
        jdbc.sql("""
                        UPDATE cranes SET faulted = FALSE, fault_code = NULL, faulted_since = NULL, updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id).update();
    }

    public void heartbeat(String id, Instant at) {
        jdbc.sql("""
                        UPDATE cranes SET last_heartbeat_at = GREATEST(COALESCE(last_heartbeat_at, :at), :at), updated_at = now()
                        WHERE id = :id
                        """)
                .param("at", Sql.ts(at)).param("id", id).update();
    }
}
