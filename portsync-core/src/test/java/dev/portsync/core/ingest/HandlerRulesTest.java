package dev.portsync.core.ingest;

import dev.portsync.core.ingest.handler.CraneEventHandler;
import dev.portsync.core.ingest.handler.GateEventHandler;
import dev.portsync.core.ingest.handler.YardService;
import dev.portsync.core.store.ContainerHistoryRepository;
import dev.portsync.core.store.ContainerRepository;
import dev.portsync.core.store.ContainerRow;
import dev.portsync.core.store.CraneMoveRepository;
import dev.portsync.core.store.CraneRepository;
import dev.portsync.core.store.CraneRow;
import dev.portsync.core.store.TruckVisitRepository;
import dev.portsync.core.store.VesselRepository;
import dev.portsync.events.ContainerNumber;
import dev.portsync.events.EventType;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.payload.CraneMove;
import dev.portsync.events.payload.GateOut;
import dev.portsync.events.payload.MoveKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The three "impossible event" rules from the spec, verified at the handler level with mocks. */
@ExtendWith(MockitoExtension.class)
class HandlerRulesTest {

    private static final Instant T = Instant.parse("2026-10-14T08:00:00Z");

    @Mock
    CraneRepository cranes;
    @Mock
    VesselRepository vessels;
    @Mock
    ContainerRepository containers;
    @Mock
    CraneMoveRepository moves;
    @Mock
    ContainerHistoryRepository history;
    @Mock
    YardService yard;
    @Mock
    TruckVisitRepository visits;

    @Test
    @DisplayName("bad check digit is rejected before any state is read")
    void badCheckDigit() {
        CraneEventHandler handler = new CraneEventHandler(cranes, vessels, containers, moves, history, yard);
        String bad = ContainerNumber.withWrongCheckDigit("CSQU3054383");
        TerminalEvent event = TerminalEvent.of(EventType.CRANE_MOVE, T, bad, "test",
                new CraneMove("QC01", "V-0001", MoveKind.DISCHARGE));

        assertThatThrownBy(() -> handler.move(event))
                .isInstanceOfSatisfying(RejectedEventException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.reason()).isEqualTo(RejectReason.INVALID_CHECK_DIGIT));
        verifyNoInteractions(cranes, vessels, containers);
    }

    @Test
    @DisplayName("crane move on a faulted crane is rejected and nothing is written")
    void moveOnFaultedCrane() {
        CraneEventHandler handler = new CraneEventHandler(cranes, vessels, containers, moves, history, yard);
        when(cranes.lock("QC02")).thenReturn(Optional.of(
                new CraneRow("QC02", "B1", T, true, "SPREADER_JAM", T.minusSeconds(600), T)));
        TerminalEvent event = TerminalEvent.of(EventType.CRANE_MOVE, T, "CSQU3054383", "test",
                new CraneMove("QC02", "V-0001", MoveKind.DISCHARGE));

        assertThatThrownBy(() -> handler.move(event))
                .isInstanceOfSatisfying(RejectedEventException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.reason()).isEqualTo(RejectReason.CRANE_FAULTED));
        verifyNoInteractions(moves, history, yard);
    }

    @Test
    @DisplayName("gate-out of a container that is not in the yard is rejected")
    void gateOutOfContainerNotInYard() {
        GateEventHandler handler = new GateEventHandler(visits, containers, history, yard);
        when(visits.lock("TV-9")).thenReturn(Optional.empty());
        when(containers.lock("CSQU3054383")).thenReturn(Optional.of(new ContainerRow("CSQU3054383", "LOADED", "V-1", T)));
        TerminalEvent event = TerminalEvent.of(EventType.GATE_OUT, T, "CSQU3054383", "test", new GateOut("TV-9", "G1"));

        assertThatThrownBy(() -> handler.gateOut(event))
                .isInstanceOfSatisfying(RejectedEventException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.reason()).isEqualTo(RejectReason.CONTAINER_NOT_IN_YARD));
        verifyNoInteractions(yard, history);
    }

    @Test
    @DisplayName("an unknown crane id is rejected as unknown equipment")
    void unknownCrane() {
        CraneEventHandler handler = new CraneEventHandler(cranes, vessels, containers, moves, history, yard);
        TerminalEvent event = TerminalEvent.of(EventType.CRANE_MOVE, T, "CSQU3054383", "test",
                new CraneMove("QC99", "V-0001", MoveKind.LOAD));
        assertThatThrownBy(() -> handler.move(event))
                .isInstanceOfSatisfying(RejectedEventException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.reason()).isEqualTo(RejectReason.UNKNOWN_EQUIPMENT));
    }

    @Test
    @DisplayName("a repeated FAULT keeps the original fault start time")
    void repeatedFaultKeepsStartTime() {
        CraneEventHandler handler = new CraneEventHandler(cranes, vessels, containers, moves, history, yard);
        when(cranes.lock("QC03")).thenReturn(Optional.of(new CraneRow("QC03", null, null, true, "HOIST", T, null)));
        handler.status(TerminalEvent.of(EventType.CRANE_STATUS, T.plusSeconds(300), "QC03", "test",
                new dev.portsync.events.payload.CraneStatus(dev.portsync.events.payload.CraneSignal.FAULT, "HOIST")));
        verify(cranes, org.mockito.Mockito.never()).fault(anyString(), anyString(), any());
    }
}
