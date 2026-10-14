package dev.portsync.core.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class BerthRepository {

    private final JdbcClient jdbc;

    public BerthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record BerthRow(String id, String vesselId) {
    }

    public List<BerthRow> findAll() {
        return jdbc.sql("SELECT id, vessel_id FROM berths ORDER BY id")
                .query((rs, n) -> new BerthRow(rs.getString("id"), rs.getString("vessel_id")))
                .list();
    }

    /** Row-locks a berth for the rest of the transaction. */
    public Optional<BerthRow> lock(String id) {
        return jdbc.sql("SELECT id, vessel_id FROM berths WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query((rs, n) -> new BerthRow(rs.getString("id"), rs.getString("vessel_id")))
                .optional();
    }

    public void setVessel(String berthId, String vesselId) {
        jdbc.sql("UPDATE berths SET vessel_id = :vessel, updated_at = now() WHERE id = :id")
                .param("vessel", vesselId).param("id", berthId).update();
    }
}
