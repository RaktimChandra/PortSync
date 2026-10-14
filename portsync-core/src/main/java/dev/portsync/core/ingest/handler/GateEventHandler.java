package dev.portsync.core.ingest.handler;

import dev.portsync.core.ingest.RejectReason;
import dev.portsync.core.ingest.RejectedEventException;
import dev.portsync.core.ingest.Scope;
import dev.portsync.core.store.ContainerHistoryRepository;
import dev.portsync.core.store.ContainerRepository;
import dev.portsync.core.store.ContainerRow;
import dev.portsync.core.store.TruckVisitRepository;
import dev.portsync.core.store.TruckVisitRow;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.payload.GateDirection;
import dev.portsync.events.payload.GateIn;
import dev.portsync.events.payload.GateOut;
import dev.portsync.events.yard.SlotRef;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** GATE_IN and GATE_OUT: truck visits that drop off exports or pick up imports. */
@Component
public class GateEventHandler {

    private final TruckVisitRepository visits;
    private final ContainerRepository containers;
    private final ContainerHistoryRepository history;
    private final YardService yard;

    public GateEventHandler(TruckVisitRepository visits, ContainerRepository containers,
                            ContainerHistoryRepository history, YardService yard) {
        this.visits = visits;
        this.containers = containers;
        this.history = history;
        this.yard = yard;
    }

    public Set<Scope> gateIn(TerminalEvent event) {
        String container = Checks.container(event.entityId());
        GateIn p = Checks.payload(event, GateIn.class);
        Checks.present(p.truckVisitId(), "truckVisitId");
        Checks.present(p.direction(), "direction");
        Checks.lane(p.lane());
        visits.lock(p.truckVisitId()).ifPresent(v -> {
            throw new RejectedEventException(RejectReason.TRUCK_VISIT_CONFLICT,
                    "Truck visit " + v.id() + " already passed the in-gate");
        });

        Optional<ContainerRow> existing = containers.lock(container);
        if (p.direction() == GateDirection.DROP_OFF) {
            existing.filter(c -> !c.status().equals("LOADED") && !c.status().equals("GATED_OUT")).ifPresent(c -> {
                throw new RejectedEventException(RejectReason.CONTAINER_STATE_CONFLICT,
                        "Cannot drop off " + container + ": it is already in the terminal with status " + c.status());
            });
            containers.upsert(container, "DELIVERED", null, event.occurredAt());
            history.add(container, event.eventId(), event.type().name(), event.occurredAt(),
                    "Delivered by truck " + p.truckPlate() + " through lane " + p.lane() + " (visit " + p.truckVisitId() + ")");
        } else {
            existing.filter(c -> c.status().equals("IN_YARD")).orElseThrow(() ->
                    new RejectedEventException(RejectReason.CONTAINER_NOT_IN_YARD,
                            "Truck " + p.truckPlate() + " came to collect " + container + " but it is not in the yard"));
            if (visits.hasOpenPickup(container)) {
                throw new RejectedEventException(RejectReason.TRUCK_VISIT_CONFLICT,
                        "Another truck is already collecting " + container);
            }
            history.add(container, event.eventId(), event.type().name(), event.occurredAt(),
                    "Truck " + p.truckPlate() + " arrived at lane " + p.lane() + " to collect it (visit " + p.truckVisitId() + ")");
        }
        visits.open(p.truckVisitId(), container, p.direction().name(), p.lane(), p.truckPlate(), event.occurredAt());
        return EnumSet.of(Scope.GATE, Scope.CONTAINERS);
    }

    public Set<Scope> gateOut(TerminalEvent event) {
        String container = Checks.container(event.entityId());
        GateOut p = Checks.payload(event, GateOut.class);
        Checks.present(p.truckVisitId(), "truckVisitId");
        Checks.lane(p.lane());

        Optional<TruckVisitRow> found = visits.lock(p.truckVisitId());
        Optional<ContainerRow> row = containers.lock(container);
        boolean inYard = row.map(c -> c.status().equals("IN_YARD")).orElse(false);
        if (found.isEmpty()) {
            if (!inYard) {
                throw new RejectedEventException(RejectReason.CONTAINER_NOT_IN_YARD,
                        "Gate-out of " + container + " which is not in the yard (no truck visit " + p.truckVisitId() + ")");
            }
            throw new RejectedEventException(RejectReason.UNKNOWN_TRUCK_VISIT,
                    "Truck visit " + p.truckVisitId() + " never passed the in-gate");
        }
        TruckVisitRow visit = found.get();
        if (!"OPEN".equals(visit.status())) {
            throw new RejectedEventException(RejectReason.TRUCK_VISIT_CONFLICT, "Truck visit " + visit.id() + " already left");
        }
        if (!visit.containerNumber().equals(container)) {
            throw new RejectedEventException(RejectReason.TRUCK_VISIT_CONFLICT,
                    "Truck visit " + visit.id() + " is for " + visit.containerNumber() + ", not " + container);
        }

        long turnMinutes = Duration.between(visit.gateInAt(), event.occurredAt()).toMinutes();
        if (GateDirection.PICK_UP.name().equals(visit.direction())) {
            if (!inYard) {
                throw new RejectedEventException(RejectReason.CONTAINER_NOT_IN_YARD,
                        "Gate-out pick-up of " + container + " which is no longer in the yard");
            }
            SlotRef from = yard.remove(container, event.occurredAt(), event.eventId());
            containers.setStatus(container, "GATED_OUT", event.occurredAt());
            history.add(container, event.eventId(), event.type().name(), event.occurredAt(),
                    "Left the terminal on truck " + visit.truckPlate() + " from " + from.label()
                            + " through lane " + p.lane() + " (turn time " + turnMinutes + " min)");
        } else {
            history.add(container, event.eventId(), event.type().name(), event.occurredAt(),
                    "Delivering truck " + visit.truckPlate() + " left through lane " + p.lane()
                            + " (turn time " + turnMinutes + " min)");
        }
        visits.close(visit.id(), event.occurredAt());
        return EnumSet.of(Scope.GATE, Scope.YARD, Scope.CONTAINERS);
    }
}
