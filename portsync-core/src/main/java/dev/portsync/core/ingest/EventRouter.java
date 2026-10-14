package dev.portsync.core.ingest;

import dev.portsync.core.ingest.handler.CraneEventHandler;
import dev.portsync.core.ingest.handler.GateEventHandler;
import dev.portsync.core.ingest.handler.VesselEventHandler;
import dev.portsync.core.ingest.handler.YardEventHandler;
import dev.portsync.events.TerminalEvent;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Dispatches an event to the handler for its type. The switch is exhaustive over EventType. */
@Component
public class EventRouter {

    private final VesselEventHandler vessels;
    private final CraneEventHandler cranes;
    private final GateEventHandler gate;
    private final YardEventHandler yard;

    public EventRouter(VesselEventHandler vessels, CraneEventHandler cranes, GateEventHandler gate, YardEventHandler yard) {
        this.vessels = vessels;
        this.cranes = cranes;
        this.gate = gate;
        this.yard = yard;
    }

    public Set<Scope> apply(TerminalEvent event) {
        return switch (event.type()) {
            case VESSEL_ARRIVED -> vessels.arrived(event);
            case BERTH_ASSIGNED -> vessels.berthAssigned(event);
            case VESSEL_DEPARTED -> vessels.departed(event);
            case CRANE_STATUS -> cranes.status(event);
            case CRANE_MOVE -> cranes.move(event);
            case GATE_IN -> gate.gateIn(event);
            case GATE_OUT -> gate.gateOut(event);
            case YARD_MOVE -> yard.move(event);
        };
    }
}
