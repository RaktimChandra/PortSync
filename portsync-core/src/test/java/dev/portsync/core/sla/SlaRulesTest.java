package dev.portsync.core.sla;

import dev.portsync.core.sla.rules.BerthWaitRule;
import dev.portsync.core.sla.rules.CraneFaultRule;
import dev.portsync.core.sla.rules.CraneProductivityRule;
import dev.portsync.core.sla.rules.TruckTurnTimeRule;
import dev.portsync.core.sla.rules.YardUtilisationRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Each SLA rule is a pure function of a snapshot, so every boundary can be tested exactly. */
class SlaRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    private static SlaSnapshot snapshot(List<SlaSnapshot.Crane> cranes, Map<String, Integer> moves,
                                        List<SlaSnapshot.WaitingVessel> vessels,
                                        List<SlaSnapshot.OpenTruckVisit> trucks, List<SlaSnapshot.Block> blocks) {
        return new SlaSnapshot(NOW, cranes, moves, vessels, trucks, blocks);
    }

    private static SlaSnapshot.Crane working(String id, Duration assignedAgo) {
        return new SlaSnapshot.Crane(id, "B1", NOW.minus(assignedAgo), false, null, null);
    }

    @Nested
    @DisplayName("crane productivity (min 20 moves/h over 60 min)")
    class Productivity {

        private final CraneProductivityRule rule = new CraneProductivityRule(20, Duration.ofMinutes(60));

        @Test
        void belowMinimumBreaches() {
            List<Breach> breaches = rule.evaluate(snapshot(List.of(working("QC01", Duration.ofHours(2))),
                    Map.of("QC01", 12), List.of(), List.of(), List.of()));
            assertThat(breaches).singleElement().satisfies(b -> {
                assertThat(b.entityId()).isEqualTo("QC01");
                assertThat(b.observed()).isEqualTo(12.0);
                assertThat(b.threshold()).isEqualTo(20.0);
            });
        }

        @Test
        void exactlyAtMinimumIsFine() {
            assertThat(rule.evaluate(snapshot(List.of(working("QC01", Duration.ofHours(2))),
                    Map.of("QC01", 20), List.of(), List.of(), List.of()))).isEmpty();
        }

        @Test
        void craneWithNoMovesAtAllBreaches() {
            assertThat(rule.evaluate(snapshot(List.of(working("QC02", Duration.ofHours(2))),
                    Map.of(), List.of(), List.of(), List.of()))).hasSize(1);
        }

        @Test
        void craneAssignedLessThanAWindowAgoIsNotJudgedYet() {
            assertThat(rule.evaluate(snapshot(List.of(working("QC01", Duration.ofMinutes(30))),
                    Map.of(), List.of(), List.of(), List.of()))).isEmpty();
        }

        @Test
        void idleAndFaultedCranesAreSkipped() {
            SlaSnapshot.Crane idle = new SlaSnapshot.Crane("QC03", null, null, false, null, null);
            SlaSnapshot.Crane faulted = new SlaSnapshot.Crane("QC04", "B2", NOW.minus(Duration.ofHours(3)), true,
                    "HOIST", NOW.minus(Duration.ofMinutes(40)));
            assertThat(rule.evaluate(snapshot(List.of(idle, faulted), Map.of(), List.of(), List.of(), List.of())))
                    .isEmpty();
        }

        @Test
        void shorterWindowScalesToPerHour() {
            CraneProductivityRule halfHour = new CraneProductivityRule(20, Duration.ofMinutes(30));
            // 9 moves in 30 min = 18/h -> breach; 10 moves = 20/h -> fine
            assertThat(halfHour.evaluate(snapshot(List.of(working("QC01", Duration.ofHours(1))),
                    Map.of("QC01", 9), List.of(), List.of(), List.of()))).hasSize(1);
            assertThat(halfHour.evaluate(snapshot(List.of(working("QC01", Duration.ofHours(1))),
                    Map.of("QC01", 10), List.of(), List.of(), List.of()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("crane fault duration (max 15 min)")
    class Fault {

        private final CraneFaultRule rule = new CraneFaultRule(Duration.ofMinutes(15));

        private SlaSnapshot faultedFor(Duration d) {
            return snapshot(List.of(new SlaSnapshot.Crane("QC05", "B3", NOW.minus(Duration.ofHours(1)), true,
                    "SPREADER_JAM", NOW.minus(d))), Map.of(), List.of(), List.of(), List.of());
        }

        @Test
        void longerThanMaxIsCritical() {
            assertThat(rule.evaluate(faultedFor(Duration.ofMinutes(16)))).singleElement()
                    .satisfies(b -> {
                        assertThat(b.severity()).isEqualTo(Severity.CRITICAL);
                        assertThat(b.message()).contains("SPREADER_JAM").contains("16 min");
                    });
        }

        @Test
        void exactlyMaxIsNotYetABreach() {
            assertThat(rule.evaluate(faultedFor(Duration.ofMinutes(15)))).isEmpty();
        }

        @Test
        void healthyCraneIsFine() {
            assertThat(rule.evaluate(snapshot(List.of(working("QC01", Duration.ofHours(1))), Map.of(), List.of(),
                    List.of(), List.of()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("vessel berth wait (max 90 min)")
    class BerthWait {

        private final BerthWaitRule rule = new BerthWaitRule(Duration.ofMinutes(90));

        @Test
        void onlyVesselsWaitingTooLongBreach() {
            List<Breach> breaches = rule.evaluate(snapshot(List.of(), Map.of(), List.of(
                    new SlaSnapshot.WaitingVessel("V-0001", "Aurora", NOW.minus(Duration.ofMinutes(91))),
                    new SlaSnapshot.WaitingVessel("V-0002", "Borealis", NOW.minus(Duration.ofMinutes(90))),
                    new SlaSnapshot.WaitingVessel("V-0003", "Cassiopeia", NOW.minus(Duration.ofMinutes(5)))),
                    List.of(), List.of()));
            assertThat(breaches).extracting(Breach::entityId).containsExactly("V-0001");
        }
    }

    @Nested
    @DisplayName("truck turn time (max 45 min)")
    class TruckTurn {

        private final TruckTurnTimeRule rule = new TruckTurnTimeRule(Duration.ofMinutes(45));

        @Test
        void truckInsideTooLongBreaches() {
            List<Breach> breaches = rule.evaluate(snapshot(List.of(), Map.of(), List.of(), List.of(
                    new SlaSnapshot.OpenTruckVisit("TV-1", "CSQU3054383", "PICK_UP", "G1", NOW.minus(Duration.ofMinutes(50))),
                    new SlaSnapshot.OpenTruckVisit("TV-2", "MSKU9070323", "DROP_OFF", "G2", NOW.minus(Duration.ofMinutes(10)))),
                    List.of()));
            assertThat(breaches).singleElement().satisfies(b -> {
                assertThat(b.entityId()).isEqualTo("TV-1");
                assertThat(b.observed()).isEqualTo(50.0);
            });
        }
    }

    @Nested
    @DisplayName("yard block utilisation (max 85%, critical 95%)")
    class Utilisation {

        private final YardUtilisationRule rule = new YardUtilisationRule(0.85, 0.95);

        @Test
        void thresholdsAreStrictAndSeverityEscalates() {
            List<Breach> breaches = rule.evaluate(snapshot(List.of(), Map.of(), List.of(), List.of(), List.of(
                    new SlaSnapshot.Block("A", 204, 240),   // exactly 85%: fine
                    new SlaSnapshot.Block("B", 205, 240),   // 85.4%: warning
                    new SlaSnapshot.Block("C", 228, 240),   // 95%: critical
                    new SlaSnapshot.Block("D", 0, 240))));
            assertThat(breaches).extracting(Breach::entityId).containsExactly("B", "C");
            assertThat(breaches.get(0).severity()).isEqualTo(Severity.WARNING);
            assertThat(breaches.get(1).severity()).isEqualTo(Severity.CRITICAL);
            assertThat(breaches.get(0).observed()).isEqualTo(85.4);
        }
    }
}
