package dev.portsync.core.ingest.handler;

import dev.portsync.core.ingest.RejectReason;
import dev.portsync.core.ingest.RejectedEventException;
import dev.portsync.core.ingest.Scope;
import dev.portsync.core.store.ContainerHistoryRepository;
import dev.portsync.core.store.ContainerRepository;
import dev.portsync.core.store.ContainerRow;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.payload.YardMove;
import dev.portsync.events.yard.SlotRef;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/** YARD_MOVE: a container is put down in a slot (from the quay, the gate, or another slot). */
@Component
public class YardEventHandler {

    private static final Set<String> MOVABLE = Set.of("ON_QUAY", "DELIVERED", "IN_YARD");

    private final ContainerRepository containers;
    private final ContainerHistoryRepository history;
    private final YardService yard;
    private final Counter tierAdjustments;

    public YardEventHandler(ContainerRepository containers, ContainerHistoryRepository history, YardService yard,
                            MeterRegistry registry) {
        this.containers = containers;
        this.history = history;
        this.yard = yard;
        this.tierAdjustments = Counter.builder("portsync.yard.tier.adjustments")
                .description("Placements that landed on a different tier than reported (cross-topic ordering)")
                .register(registry);
    }

    public Set<Scope> move(TerminalEvent event) {
        String container = Checks.container(event.entityId());
        YardMove p = Checks.payload(event, YardMove.class);
        Checks.present(p.block(), "block");
        ContainerRow row = containers.lock(container).orElseThrow(() ->
                new RejectedEventException(RejectReason.CONTAINER_STATE_CONFLICT,
                        "Unknown container " + container + ": it was never discharged or delivered"));
        if (!MOVABLE.contains(row.status())) {
            throw new RejectedEventException(RejectReason.CONTAINER_STATE_CONFLICT,
                    "Container " + container + " has status " + row.status() + " and cannot be put in the yard");
        }
        SlotRef requested = p.slot();
        YardService.Placement placement = yard.place(container, requested, event.occurredAt(), event.eventId());
        containers.setStatus(container, "IN_YARD", event.occurredAt());
        String where = placement.slot().label();
        String description = placement.from()
                .map(f -> "Moved in the yard from " + f.label() + " to " + where)
                .orElse("Stacked in the yard at " + where);
        if (placement.adjusted()) {
            tierAdjustments.increment();
            description += " (reported tier " + placement.reportedTier()
                    + "; the stack held a different number of boxes when this event was applied)";
        }
        history.add(container, event.eventId(), event.type().name(), event.occurredAt(), description);
        return EnumSet.of(Scope.YARD, Scope.CONTAINERS);
    }
}
