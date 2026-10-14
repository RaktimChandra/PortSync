package dev.portsync.core.sla;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything the SLA rules need, read once per evaluation. Rules are pure functions of a snapshot,
 * which is what makes them unit-testable without a database or a clock.
 *
 * @param now              simulated time of the evaluation
 * @param cranes           all quay cranes
 * @param movesInWindow    crane id -> moves completed in the productivity window ending at {@code now}
 * @param waitingVessels   vessels that arrived and are not yet berthed
 * @param openTruckVisits  trucks inside the terminal
 * @param blocks           occupancy per yard block
 */
public record SlaSnapshot(
        Instant now,
        List<Crane> cranes,
        Map<String, Integer> movesInWindow,
        List<WaitingVessel> waitingVessels,
        List<OpenTruckVisit> openTruckVisits,
        List<Block> blocks) {

    public record Crane(String id, String berthId, Instant assignedAt, boolean faulted, String faultCode,
                        Instant faultedSince) {
    }

    public record WaitingVessel(String id, String name, Instant arrivedAt) {
    }

    public record OpenTruckVisit(String id, String containerNumber, String direction, String lane, Instant gateInAt) {
    }

    public record Block(String block, int used, int capacity) {

        public double ratio() {
            return capacity == 0 ? 0 : used / (double) capacity;
        }
    }
}
