package dev.portsync.core.ingest.handler;

import dev.portsync.core.ingest.RejectReason;
import dev.portsync.core.ingest.RejectedEventException;
import dev.portsync.core.ingest.Scope;
import dev.portsync.core.store.ContainerHistoryRepository;
import dev.portsync.core.store.ContainerRepository;
import dev.portsync.core.store.ContainerRow;
import dev.portsync.core.store.CraneMoveRepository;
import dev.portsync.core.store.CraneRepository;
import dev.portsync.core.store.CraneRow;
import dev.portsync.core.store.VesselRepository;
import dev.portsync.core.store.VesselRow;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.payload.CraneMove;
import dev.portsync.events.payload.CraneStatus;
import dev.portsync.events.payload.MoveKind;
import dev.portsync.events.yard.SlotRef;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** CRANE_STATUS (heartbeat / fault / recovered) and CRANE_MOVE (discharge / load). */
@Component
public class CraneEventHandler {

    private final CraneRepository cranes;
    private final VesselRepository vessels;
    private final ContainerRepository containers;
    private final CraneMoveRepository moves;
    private final ContainerHistoryRepository history;
    private final YardService yard;

    public CraneEventHandler(CraneRepository cranes, VesselRepository vessels, ContainerRepository containers,
                             CraneMoveRepository moves, ContainerHistoryRepository history, YardService yard) {
        this.cranes = cranes;
        this.vessels = vessels;
        this.containers = containers;
        this.moves = moves;
        this.history = history;
        this.yard = yard;
    }

    public Set<Scope> status(TerminalEvent event) {
        Checks.crane(event.entityId());
        CraneStatus p = Checks.payload(event, CraneStatus.class);
        Checks.present(p.signal(), "signal");
        CraneRow crane = cranes.lock(event.entityId()).orElseThrow();
        switch (p.signal()) {
            case HEARTBEAT -> cranes.heartbeat(crane.id(), event.occurredAt());
            case FAULT -> {
                // A repeated FAULT keeps the original start time, so "faulted for N minutes" stays honest.
                if (!crane.faulted()) {
                    cranes.fault(crane.id(), p.faultCode() == null ? "UNSPECIFIED" : p.faultCode(), event.occurredAt());
                }
            }
            case RECOVERED -> {
                if (crane.faulted()) {
                    cranes.recover(crane.id());
                }
            }
        }
        return EnumSet.of(Scope.CRANES, Scope.BERTHS);
    }

    public Set<Scope> move(TerminalEvent event) {
        String container = Checks.container(event.entityId());
        CraneMove p = Checks.payload(event, CraneMove.class);
        Checks.present(p.kind(), "kind");
        Checks.present(p.vesselId(), "vesselId");
        Checks.crane(p.craneId());

        CraneRow crane = cranes.lock(p.craneId()).orElseThrow();
        if (crane.faulted()) {
            throw new RejectedEventException(RejectReason.CRANE_FAULTED,
                    "Crane " + crane.id() + " is faulted (" + crane.faultCode() + ") since " + crane.faultedSince()
                            + " and cannot move " + container);
        }
        VesselRow vessel = vessels.lock(p.vesselId()).orElseThrow(() ->
                new RejectedEventException(RejectReason.UNKNOWN_VESSEL, "Unknown vessel visit " + p.vesselId()));
        if (!"AT_BERTH".equals(vessel.status())) {
            throw new RejectedEventException(RejectReason.VESSEL_STATE_CONFLICT,
                    "Vessel " + vessel.id() + " is " + vessel.status() + "; cranes only work berthed vessels");
        }
        if (!vessel.berthId().equals(crane.berthId())) {
            throw new RejectedEventException(RejectReason.CRANE_NOT_ASSIGNED,
                    "Crane " + crane.id() + " is not assigned to berth " + vessel.berthId() + " where " + vessel.id() + " lies");
        }

        Optional<ContainerRow> existing = containers.lock(container);
        if (p.kind() == MoveKind.DISCHARGE) {
            existing.filter(c -> !c.status().equals("LOADED") && !c.status().equals("GATED_OUT")).ifPresent(c -> {
                throw new RejectedEventException(RejectReason.CONTAINER_STATE_CONFLICT,
                        "Cannot discharge " + container + ": it is already in the terminal with status " + c.status());
            });
            containers.upsert(container, "ON_QUAY", vessel.id(), event.occurredAt());
            vessels.countDischarge(vessel.id());
            history.add(container, event.eventId(), event.type().name(), event.occurredAt(),
                    "Discharged from " + vessel.name() + " (" + vessel.id() + ") by " + crane.id() + " at berth " + vessel.berthId());
        } else {
            ContainerRow row = existing.filter(c -> c.status().equals("IN_YARD")).orElseThrow(() ->
                    new RejectedEventException(RejectReason.CONTAINER_NOT_IN_YARD,
                            "Cannot load " + container + ": it is not in the yard"
                                    + existing.map(c -> " (status " + c.status() + ")").orElse(" (unknown container)")));
            SlotRef from = yard.remove(row.number(), event.occurredAt(), event.eventId());
            containers.setStatusAndVessel(container, "LOADED", vessel.id(), event.occurredAt());
            vessels.countLoad(vessel.id());
            history.add(container, event.eventId(), event.type().name(), event.occurredAt(),
                    "Loaded onto " + vessel.name() + " (" + vessel.id() + ") by " + crane.id() + " from " + from.label());
        }
        moves.insert(event.eventId(), crane.id(), vessel.id(), container, p.kind().name(), event.occurredAt());
        return EnumSet.of(Scope.CRANES, Scope.BERTHS, Scope.VESSELS, Scope.YARD, Scope.CONTAINERS);
    }
}
