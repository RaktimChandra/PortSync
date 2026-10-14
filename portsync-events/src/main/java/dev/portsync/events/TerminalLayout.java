package dev.portsync.events;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Physical layout of the simulated terminal. Shared by simulator and core so both agree on which
 * berths, cranes, blocks and slots exist.
 *
 * <ul>
 *   <li>3 berths (B1..B3), 6 quay cranes (QC01..QC06), 4 gate lanes (G1..G4)</li>
 *   <li>4 yard blocks (A..D), each 6 rows x 10 bays x 4 tiers = 240 slots, 960 in total</li>
 * </ul>
 */
public final class TerminalLayout {

    public static final List<String> BERTHS = List.of("B1", "B2", "B3");
    public static final List<String> CRANES = IntStream.rangeClosed(1, 6)
            .mapToObj(i -> String.format("QC%02d", i)).toList();
    public static final List<String> BLOCKS = List.of("A", "B", "C", "D");
    public static final List<String> GATE_LANES = List.of("G1", "G2", "G3", "G4");

    public static final int ROWS = 6;
    public static final int BAYS = 10;
    public static final int TIERS = 4;
    public static final int SLOTS_PER_BLOCK = ROWS * BAYS * TIERS;
    public static final int TOTAL_SLOTS = SLOTS_PER_BLOCK * 4;

    /** Number of cranes normally allocated to a berthed vessel. */
    public static final int CRANES_PER_BERTH = 2;

    private TerminalLayout() {
    }

    public static boolean isBerth(String id) {
        return BERTHS.contains(id);
    }

    public static boolean isCrane(String id) {
        return CRANES.contains(id);
    }

    public static boolean isBlock(String id) {
        return BLOCKS.contains(id);
    }

    public static boolean isLane(String id) {
        return GATE_LANES.contains(id);
    }
}
