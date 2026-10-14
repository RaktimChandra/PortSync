package dev.portsync.core.alert;

import dev.portsync.core.sla.Breach;
import dev.portsync.core.store.Sql;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AlertRepository {

    private static final String COLUMNS = "id, rule, entity_id, severity, state, message, observed_value, threshold, "
            + "opened_at, last_seen_at, acknowledged_at, resolved_at, resolution";

    private static final RowMapper<Alert> MAPPER = (rs, n) -> new Alert(
            rs.getLong("id"),
            rs.getString("rule"),
            rs.getString("entity_id"),
            rs.getString("severity"),
            AlertState.valueOf(rs.getString("state")),
            rs.getString("message"),
            Sql.nullableDouble(rs, "observed_value"),
            Sql.nullableDouble(rs, "threshold"),
            Sql.instant(rs, "opened_at"),
            Sql.instant(rs, "last_seen_at"),
            Sql.instant(rs, "acknowledged_at"),
            Sql.instant(rs, "resolved_at"),
            rs.getString("resolution"));

    private final JdbcClient jdbc;

    public AlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Alert> findActiveByRule(String rule) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM alerts WHERE rule = :rule AND state IN ('OPEN', 'ACKNOWLEDGED')")
                .param("rule", rule).query(MAPPER).list();
    }

    public Optional<Alert> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM alerts WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    /** Newest first; optionally filtered by state. */
    public List<Alert> find(Collection<AlertState> states, int limit) {
        if (states == null || states.isEmpty()) {
            return jdbc.sql("SELECT " + COLUMNS + " FROM alerts ORDER BY opened_at DESC, id DESC LIMIT :limit")
                    .param("limit", limit).query(MAPPER).list();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM alerts WHERE state IN (:states) ORDER BY opened_at DESC, id DESC LIMIT :limit")
                .param("states", states.stream().map(Enum::name).toList())
                .param("limit", limit).query(MAPPER).list();
    }

    /**
     * Opens an alert. The partial unique index on (rule, entity_id) for active alerts makes this
     * race-safe: if another evaluation opened the same alert first, nothing is inserted.
     *
     * @return true if a new alert was created
     */
    public boolean open(Breach b, Instant now) {
        return jdbc.sql("""
                        INSERT INTO alerts (rule, entity_id, severity, state, message, observed_value, threshold,
                                            opened_at, last_seen_at)
                        VALUES (:rule, :entity, :severity, 'OPEN', :message, :observed, :threshold, :now, :now)
                        ON CONFLICT (rule, entity_id) WHERE state IN ('OPEN', 'ACKNOWLEDGED') DO NOTHING
                        """)
                .param("rule", b.rule()).param("entity", b.entityId()).param("severity", b.severity().name())
                .param("message", b.message()).param("observed", b.observed()).param("threshold", b.threshold())
                .param("now", Sql.ts(now))
                .update() == 1;
    }

    public void refresh(long id, Breach b, Instant now) {
        jdbc.sql("""
                        UPDATE alerts SET message = :message, observed_value = :observed, severity = :severity,
                                          last_seen_at = :now
                        WHERE id = :id AND state IN ('OPEN', 'ACKNOWLEDGED')
                        """)
                .param("message", b.message()).param("observed", b.observed()).param("severity", b.severity().name())
                .param("now", Sql.ts(now)).param("id", id)
                .update();
    }

    /** @return true if the alert was OPEN and is now ACKNOWLEDGED */
    public boolean acknowledge(long id, Instant now) {
        return jdbc.sql("UPDATE alerts SET state = 'ACKNOWLEDGED', acknowledged_at = :now WHERE id = :id AND state = 'OPEN'")
                .param("now", Sql.ts(now)).param("id", id).update() == 1;
    }

    /** @return true if the alert was active and is now RESOLVED */
    public boolean resolve(long id, Instant now, String resolution) {
        return jdbc.sql("""
                        UPDATE alerts SET state = 'RESOLVED', resolved_at = :now, resolution = :resolution
                        WHERE id = :id AND state IN ('OPEN', 'ACKNOWLEDGED')
                        """)
                .param("now", Sql.ts(now)).param("resolution", resolution).param("id", id)
                .update() == 1;
    }

    /** Active alert count per rule (rules with none are absent). */
    public Map<String, Integer> activeCountByRule() {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.sql("SELECT rule, count(*) AS n FROM alerts WHERE state IN ('OPEN', 'ACKNOWLEDGED') GROUP BY rule")
                .query(rs -> {
                    counts.put(rs.getString("rule"), rs.getInt("n"));
                });
        return counts;
    }

    public long countByState(AlertState state) {
        return jdbc.sql("SELECT count(*) FROM alerts WHERE state = :state")
                .param("state", state.name()).query(Long.class).single();
    }
}
