package dev.portsync.core.it;

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
import dev.portsync.events.payload.VesselArrived;
import dev.portsync.events.payload.VesselDeparted;
import dev.portsync.events.payload.YardMove;
import dev.portsync.events.yard.SlotRef;

import java.time.Instant;
import java.util.List;

/** Factory methods for test events, mirroring what the simulator emits. */
final class Scenario {

    private static final String SOURCE = "it";

    private Scenario() {
    }

    static TerminalEvent arrived(String vessel, Instant at, int discharge, int load) {
        return TerminalEvent.of(EventType.VESSEL_ARRIVED, at, vessel, SOURCE, new VesselArrived("MV " + vessel, "001E", discharge, load));
    }

    static TerminalEvent berthed(String vessel, String berth, Instant at, String... cranes) {
        return TerminalEvent.of(EventType.BERTH_ASSIGNED, at, vessel, SOURCE, new BerthAssigned(berth, List.of(cranes)));
    }

    static TerminalEvent departed(String vessel, String berth, Instant at) {
        return TerminalEvent.of(EventType.VESSEL_DEPARTED, at, vessel, SOURCE, new VesselDeparted(berth));
    }

    static TerminalEvent discharge(String container, String crane, String vessel, Instant at) {
        return TerminalEvent.of(EventType.CRANE_MOVE, at, container, SOURCE, new CraneMove(crane, vessel, MoveKind.DISCHARGE));
    }

    static TerminalEvent load(String container, String crane, String vessel, Instant at) {
        return TerminalEvent.of(EventType.CRANE_MOVE, at, container, SOURCE, new CraneMove(crane, vessel, MoveKind.LOAD));
    }

    static TerminalEvent craneStatus(String crane, CraneSignal signal, Instant at) {
        return TerminalEvent.of(EventType.CRANE_STATUS, at, crane, SOURCE,
                new CraneStatus(signal, signal == CraneSignal.FAULT ? "SPREADER_JAM" : null));
    }

    static TerminalEvent dropOff(String container, String visit, Instant at) {
        return TerminalEvent.of(EventType.GATE_IN, at, container, SOURCE, new GateIn(visit, "G1", GateDirection.DROP_OFF, "TN01AB" + visit));
    }

    static TerminalEvent pickUp(String container, String visit, Instant at) {
        return TerminalEvent.of(EventType.GATE_IN, at, container, SOURCE, new GateIn(visit, "G2", GateDirection.PICK_UP, "TN02CD" + visit));
    }

    static TerminalEvent gateOut(String container, String visit, Instant at) {
        return TerminalEvent.of(EventType.GATE_OUT, at, container, SOURCE, new GateOut(visit, "G3"));
    }

    static TerminalEvent yardMove(String container, SlotRef slot, Instant at) {
        return TerminalEvent.of(EventType.YARD_MOVE, at, container, SOURCE, YardMove.to(slot));
    }
}
