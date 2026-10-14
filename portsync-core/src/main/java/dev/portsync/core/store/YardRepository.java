package dev.portsync.core.store;

import dev.portsync.events.TerminalLayout;
import dev.portsync.events.yard.SlotRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Yard slots in PostgreSQL. All writes go through a locked stack: {@link #lockStack} takes a
 * {@code FOR UPDATE} lock on the four tier rows of one (block, row, bay), so two consumer threads
 * can never interleave a placement and a removal on the same stack.
 */
@Repository
public class YardRepository {

    private final JdbcClient jdbc;

    public YardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SlotRow(String block, int row, int bay, int tier, String containerNumber) {
    }

    public record BlockOccupancy(String block, int used, int capacity) {

        public double ratio() {
            return capacity == 0 ? 0 : used / (double) capacity;
        }
    }

    public record InvariantViolation(String rule, String detail) {
    }

    /** Locks a stack and returns its occupants indexed by tier (index 0 = tier 1, null = empty). */
    public String[] lockStack(SlotRef.StackRef stack) {
        String[] occupants = new String[TerminalLayout.TIERS];
        jdbc.sql("""
                        SELECT tier, container_number FROM yard_slots
                        WHERE block = :block AND row_no = :row AND bay_no = :bay
                        ORDER BY tier
                        FOR UPDATE
                        """)
                .param("block", stack.block()).param("row", stack.row()).param("bay", stack.bay())
                .query(rs -> {
                    occupants[rs.getInt("tier") - 1] = rs.getString("container_number");
                });
        return occupants;
    }

    public void setOccupant(SlotRef slot, String containerNumber, Instant at) {
        jdbc.sql("""
                        UPDATE yard_slots SET container_number = :n, placed_at = :at
                        WHERE block = :block AND row_no = :row AND bay_no = :bay AND tier = :tier
                        """)
                .param("n", containerNumber)
                .param("at", containerNumber == null ? null : Sql.ts(at))
                .param("block", slot.block()).param("row", slot.row()).param("bay", slot.bay()).param("tier", slot.tier())
                .update();
    }

    public Optional<SlotRef> locate(String containerNumber) {
        return jdbc.sql("SELECT block, row_no, bay_no, tier FROM yard_slots WHERE container_number = :n")
                .param("n", containerNumber)
                .query((rs, i) -> new SlotRef(rs.getString("block"), rs.getInt("row_no"), rs.getInt("bay_no"),
                        rs.getInt("tier")))
                .optional();
    }

    public List<SlotRow> block(String block) {
        return jdbc.sql("""
                        SELECT block, row_no, bay_no, tier, container_number FROM yard_slots
                        WHERE block = :block ORDER BY row_no, bay_no, tier
                        """)
                .param("block", block)
                .query((rs, i) -> new SlotRow(rs.getString("block"), rs.getInt("row_no"), rs.getInt("bay_no"),
                        rs.getInt("tier"), rs.getString("container_number")))
                .list();
    }

    public List<BlockOccupancy> occupancy() {
        return jdbc.sql("""
                        SELECT block, count(container_number) AS used, count(*) AS capacity
                        FROM yard_slots GROUP BY block ORDER BY block
                        """)
                .query((rs, i) -> new BlockOccupancy(rs.getString("block"), rs.getInt("used"), rs.getInt("capacity")))
                .list();
    }

    /**
     * Audits the yard invariants straight from the database. Used by tests, by the
     * {@code /api/ops/invariants} endpoint and by a scheduled gauge.
     */
    public List<InvariantViolation> invariantViolations() {
        List<InvariantViolation> violations = new ArrayList<>();
        jdbc.sql("""
                        SELECT s.block, s.row_no, s.bay_no, s.tier, s.container_number
                        FROM yard_slots s
                        JOIN yard_slots below ON below.block = s.block AND below.row_no = s.row_no
                                             AND below.bay_no = s.bay_no AND below.tier = s.tier - 1
                        WHERE s.container_number IS NOT NULL AND below.container_number IS NULL
                        """)
                .query(rs -> {
                    violations.add(new InvariantViolation("NO_FLOATING", rs.getString("container_number") + " at "
                            + new SlotRef(rs.getString("block"), rs.getInt("row_no"), rs.getInt("bay_no"),
                            rs.getInt("tier")).label() + " floats above an empty tier"));
                });
        jdbc.sql("""
                        SELECT container_number, count(*) AS slots FROM yard_slots
                        WHERE container_number IS NOT NULL GROUP BY container_number HAVING count(*) > 1
                        """)
                .query(rs -> {
                    violations.add(new InvariantViolation("ONE_SLOT_PER_CONTAINER",
                            rs.getString("container_number") + " occupies " + rs.getInt("slots") + " slots"));
                });
        jdbc.sql("""
                        SELECT c.number FROM containers c
                        LEFT JOIN yard_slots s ON s.container_number = c.number
                        WHERE c.status = 'IN_YARD' AND s.container_number IS NULL
                        """)
                .query(rs -> {
                    violations.add(new InvariantViolation("IN_YARD_HAS_SLOT",
                            rs.getString("number") + " is IN_YARD but has no slot"));
                });
        jdbc.sql("""
                        SELECT s.container_number, c.status FROM yard_slots s
                        JOIN containers c ON c.number = s.container_number
                        WHERE c.status <> 'IN_YARD'
                        """)
                .query(rs -> {
                    violations.add(new InvariantViolation("SLOT_HOLDS_YARD_CONTAINER",
                            rs.getString("container_number") + " has a slot but status " + rs.getString("status")));
                });
        return violations;
    }
}
