package dev.portsync.simulator.engine;

import dev.portsync.events.ContainerNumber;
import dev.portsync.events.EventJson;
import dev.portsync.events.EventType;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.payload.BerthAssigned;
import dev.portsync.events.payload.CraneMove;
import dev.portsync.events.payload.CraneSignal;
import dev.portsync.events.payload.CraneStatus;
import dev.portsync.events.payload.GateDirection;
import dev.portsync.events.payload.GateIn;
import dev.portsync.events.payload.GateOut;
import dev.portsync.events.payload.MoveKind;
import dev.portsync.events.payload.VesselDeparted;
import dev.portsync.events.payload.YardMove;
import dev.portsync.events.yard.YardModel;
import dev.portsync.simulator.SimulatorProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Runs the simulation for two simulated days without Kafka and replays everything it emitted
 * through an independent checker that applies the same rules as the core service. If the
 * simulator ever produced an event the core would reject, these tests fail.
 */
class TerminalSimulationTest {

    static final Instant START = Instant.parse("2026-10-14T06:00:00Z");

    static SimulatorProperties props(long seed, double faultsPerHour) {
        return new SimulatorProperties(60, START, seed, true, true,
                Map.of("A", 0.5, "B", 0.4, "C", 0.6, "D", 0.75),
                Duration.ofMinutes(100), Duration.ofMinutes(20), 20, 40, 15, 30, 26, 24, 0.5, 0.08,
                faultsPerHour, Duration.ofMinutes(5));
    }

    static final class Recorder implements EventSink {
        final List<TerminalEvent> events = new ArrayList<>();
        final List<String> raw = new ArrayList<>();

        @Override
        public void publish(TerminalEvent event) {
            events.add(event);
        }

        @Override
        public void publishRaw(String topic, String key, String value) {
            raw.add(value);
        }
    }

    private static Recorder run(SimulatorProperties props, Duration length) {
        Recorder sink = new Recorder();
        TerminalSimulation sim = new TerminalSimulation(props, sink);
        sim.openingInventory(START).forEach(sink.events::addAll);
        sim.start(START);
        for (Instant t = START; t.isBefore(START.plus(length)); t = t.plusSeconds(60)) {
            sim.tick(t);
        }
        return sink;
    }

    @Test
    @DisplayName("48 simulated hours: every event is one the core service would accept")
    void everyEventIsValidForTheCore() {
        Recorder sink = run(props(42, 0.3), Duration.ofHours(48));
        new ReferenceChecker().replay(sink.events);
        assertThat(sink.events).hasSizeGreaterThan(5_000);
    }

    @Test
    @DisplayName("different seeds also produce only valid streams")
    void otherSeeds() {
        for (long seed : new long[]{1, 7, 2026}) {
            new ReferenceChecker().replay(run(props(seed, 0.5), Duration.ofHours(24)).events);
        }
    }

    @Test
    @DisplayName("same seed, same events: runs are reproducible")
    void deterministic() {
        List<String> a = run(props(99, 0.3), Duration.ofHours(6)).events.stream()
                .map(e -> e.type() + e.entityId() + e.occurredAt() + e.payload()).toList();
        List<String> b = run(props(99, 0.3), Duration.ofHours(6)).events.stream()
                .map(e -> e.type() + e.entityId() + e.occurredAt() + e.payload()).toList();
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("vessels arrive, berth, are worked and depart")
    void vesselsComplete() {
        List<TerminalEvent> events = run(props(42, 0.0), Duration.ofHours(24)).events;
        long arrived = events.stream().filter(e -> e.type() == EventType.VESSEL_ARRIVED).count();
        long departed = events.stream().filter(e -> e.type() == EventType.VESSEL_DEPARTED).count();
        assertThat(arrived).isGreaterThanOrEqualTo(5);
        assertThat(departed).isGreaterThanOrEqualTo(3);
        assertThat(events.stream().filter(e -> e.type() == EventType.CRANE_MOVE)).hasSizeGreaterThan(200);
    }

    @Test
    @DisplayName("injections meant to be legal (surges, slow crane, crane fault/recover) keep the stream valid")
    void legalInjectionsKeepTheStreamValid() {
        Recorder sink = new Recorder();
        TerminalSimulation sim = new TerminalSimulation(props(11, 0.2), sink);
        sim.openingInventory(START).forEach(sink.events::addAll);
        Instant t = START;
        for (; t.isBefore(START.plus(Duration.ofHours(4))); t = t.plusSeconds(60)) {
            sim.tick(t);
        }
        TerminalSimulation.Injection surge = sim.gateSurge(null, null);
        assertThat(surge.events()).hasSizeGreaterThanOrEqualTo(20);
        sim.vesselSurge(4);
        sim.slowCrane("QC01", 0.3);
        sim.faultCrane("QC05", "TEST");
        for (; t.isBefore(START.plus(Duration.ofHours(6))); t = t.plusSeconds(60)) {
            sim.tick(t);
        }
        sim.recoverCrane("QC05");
        for (; t.isBefore(START.plus(Duration.ofHours(10))); t = t.plusSeconds(60)) {
            sim.tick(t);
        }
        new ReferenceChecker().replay(sink.events);
    }

    @Test
    @DisplayName("gate surge pushes the fullest block past 85%")
    void gateSurgeFillsTheFullestBlock() {
        Recorder sink = new Recorder();
        TerminalSimulation sim = new TerminalSimulation(props(3, 0.0), sink);
        sim.openingInventory(START).forEach(sink.events::addAll);
        sim.tick(START);
        sim.gateSurge(null, null);
        for (Instant t = START; t.isBefore(START.plus(Duration.ofMinutes(60))); t = t.plusSeconds(60)) {
            sim.tick(t);
        }
        int fullest = sim.status().yardOccupancy().values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        assertThat(fullest / 240.0).isGreaterThan(0.85);
    }

    @Test
    @DisplayName("fault injection produces exactly the invalid events it promises")
    void injectionsAreInvalidOnPurpose() {
        Recorder sink = new Recorder();
        TerminalSimulation sim = new TerminalSimulation(props(5, 0.0), sink);
        sim.openingInventory(START).forEach(sink.events::addAll);
        for (Instant t = START; t.isBefore(START.plus(Duration.ofHours(3))); t = t.plusSeconds(60)) {
            sim.tick(t);
        }
        int before = sink.events.size();

        TerminalSimulation.Injection invalid = sim.invalidContainer();
        assertThat(ContainerNumber.isValid(sink.events.get(sink.events.size() - 1).entityId())).isFalse();
        assertThat(invalid.expectedOutcome()).contains("INVALID_CHECK_DIGIT");

        sim.duplicates(5);
        List<TerminalEvent> dupes = sink.events.subList(sink.events.size() - 5, sink.events.size());
        assertThat(dupes).allSatisfy(d -> assertThat(sink.events.subList(0, before)).anyMatch(o -> o.eventId().equals(d.eventId())));

        sim.outOfOrder();
        TerminalEvent stale = sink.events.get(sink.events.size() - 1);
        Instant newestForEntity = sink.events.subList(0, sink.events.size() - 1).stream()
                .filter(e -> e.entityId().equals(stale.entityId())).map(TerminalEvent::occurredAt)
                .max(Instant::compareTo).orElseThrow();
        assertThat(stale.occurredAt()).isBefore(newestForEntity);

        sim.impossible(TerminalSimulation.ImpossibleKind.MALFORMED_JSON);
        assertThat(sink.raw).singleElement().satisfies(r -> {
            try {
                EventJson.read(r);
                fail("should not parse");
            } catch (dev.portsync.events.MalformedEventException expected) {
                // expected
            }
        });
    }

    /**
     * Independent re-implementation of the core's acceptance rules, kept deliberately simple.
     * Fails on the first event the core would dead-letter or ignore as stale.
     */
    static final class ReferenceChecker {
        final YardModel yard = new YardModel();
        final Map<String, String> containerStatus = new HashMap<>();
        final Map<String, String> openVisits = new HashMap<>();   // visit -> container
        final Map<String, GateDirection> visitDirection = new HashMap<>();
        final Map<String, String> craneBerth = new HashMap<>();
        final Set<String> faulted = new HashSet<>();
        final Map<String, String> vesselStatus = new HashMap<>();
        final Map<String, String> vesselBerth = new HashMap<>();
        final Map<String, Instant> lastAt = new HashMap<>();

        void replay(List<TerminalEvent> events) {
            for (TerminalEvent e : events) {
                String key = e.type().entityKind() + ":" + e.entityId();
                Instant last = lastAt.get(key);
                if (last != null && e.occurredAt().isBefore(last)) {
                    fail("Out of order: " + e.type() + " " + e.entityId() + " at " + e.occurredAt() + " after " + last);
                }
                lastAt.put(key, e.occurredAt());
                try {
                    apply(e);
                } catch (RuntimeException ex) {
                    fail("Core would reject " + e.type() + " " + e.entityId() + " at " + e.occurredAt() + ": " + ex.getMessage());
                }
            }
            assertThat(yard.invariantViolations()).isEmpty();
        }

        void apply(TerminalEvent e) {
            switch (e.type()) {
                case VESSEL_ARRIVED -> require(vesselStatus.put(e.entityId(), "WAITING") == null, "vessel exists");
                case BERTH_ASSIGNED -> {
                    BerthAssigned p = e.payloadAs(BerthAssigned.class);
                    require("WAITING".equals(vesselStatus.get(e.entityId())), "vessel not waiting");
                    require(!vesselBerth.containsValue(p.berthId()), "berth occupied");
                    for (String c : p.craneIds()) {
                        require(!craneBerth.containsKey(c), c + " busy");
                        craneBerth.put(c, p.berthId());
                    }
                    vesselStatus.put(e.entityId(), "AT_BERTH");
                    vesselBerth.put(e.entityId(), p.berthId());
                }
                case VESSEL_DEPARTED -> {
                    VesselDeparted p = e.payloadAs(VesselDeparted.class);
                    require(p.berthId().equals(vesselBerth.get(e.entityId())), "not at that berth");
                    vesselStatus.put(e.entityId(), "DEPARTED");
                    vesselBerth.remove(e.entityId());
                    craneBerth.values().removeIf(b -> b.equals(p.berthId()));
                }
                case CRANE_STATUS -> {
                    CraneStatus p = e.payloadAs(CraneStatus.class);
                    if (p.signal() == CraneSignal.FAULT) {
                        faulted.add(e.entityId());
                    } else if (p.signal() == CraneSignal.RECOVERED) {
                        faulted.remove(e.entityId());
                    }
                }
                case CRANE_MOVE -> {
                    require(ContainerNumber.isValid(e.entityId()), "check digit");
                    CraneMove p = e.payloadAs(CraneMove.class);
                    require(!faulted.contains(p.craneId()), "crane faulted");
                    require("AT_BERTH".equals(vesselStatus.get(p.vesselId())), "vessel not berthed");
                    require(vesselBerth.get(p.vesselId()).equals(craneBerth.get(p.craneId())), "crane not assigned");
                    if (p.kind() == MoveKind.DISCHARGE) {
                        String s = containerStatus.get(e.entityId());
                        require(s == null || s.equals("LOADED") || s.equals("GATED_OUT"), "already on site");
                        containerStatus.put(e.entityId(), "ON_QUAY");
                    } else {
                        require("IN_YARD".equals(containerStatus.get(e.entityId())), "not in yard");
                        yard.remove(e.entityId());
                        containerStatus.put(e.entityId(), "LOADED");
                    }
                }
                case GATE_IN -> {
                    require(ContainerNumber.isValid(e.entityId()), "check digit");
                    GateIn p = e.payloadAs(GateIn.class);
                    require(!openVisits.containsKey(p.truckVisitId()) && !visitDirection.containsKey(p.truckVisitId()), "visit reused");
                    if (p.direction() == GateDirection.DROP_OFF) {
                        String s = containerStatus.get(e.entityId());
                        require(s == null || s.equals("LOADED") || s.equals("GATED_OUT"), "already on site");
                        containerStatus.put(e.entityId(), "DELIVERED");
                    } else {
                        require("IN_YARD".equals(containerStatus.get(e.entityId())), "pickup not in yard");
                        require(!openVisits.containsValue(e.entityId()), "double pickup");
                    }
                    openVisits.put(p.truckVisitId(), e.entityId());
                    visitDirection.put(p.truckVisitId(), p.direction());
                }
                case GATE_OUT -> {
                    GateOut p = e.payloadAs(GateOut.class);
                    require(e.entityId().equals(openVisits.remove(p.truckVisitId())), "unknown visit");
                    if (visitDirection.get(p.truckVisitId()) == GateDirection.PICK_UP) {
                        require("IN_YARD".equals(containerStatus.get(e.entityId())), "not in yard");
                        yard.remove(e.entityId());
                        containerStatus.put(e.entityId(), "GATED_OUT");
                    }
                }
                case YARD_MOVE -> {
                    String s = containerStatus.get(e.entityId());
                    require("ON_QUAY".equals(s) || "DELIVERED".equals(s) || "IN_YARD".equals(s), "cannot stack " + s);
                    yard.place(e.entityId(), e.payloadAs(YardMove.class).slot());
                    containerStatus.put(e.entityId(), "IN_YARD");
                }
                default -> fail("unexpected type " + e.type());
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalStateException(message);
            }
        }
    }
}
