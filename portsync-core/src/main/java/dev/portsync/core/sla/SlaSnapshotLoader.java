package dev.portsync.core.sla;

import dev.portsync.core.config.PortSyncProperties;
import dev.portsync.core.store.CraneMoveRepository;
import dev.portsync.core.store.CraneRepository;
import dev.portsync.core.store.TruckVisitRepository;
import dev.portsync.core.store.VesselRepository;
import dev.portsync.core.store.YardRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Reads one consistent snapshot of the data the SLA rules need. */
@Component
public class SlaSnapshotLoader {

    private final CraneRepository cranes;
    private final CraneMoveRepository moves;
    private final VesselRepository vessels;
    private final TruckVisitRepository visits;
    private final YardRepository yard;
    private final PortSyncProperties properties;

    public SlaSnapshotLoader(CraneRepository cranes, CraneMoveRepository moves, VesselRepository vessels,
                             TruckVisitRepository visits, YardRepository yard, PortSyncProperties properties) {
        this.cranes = cranes;
        this.moves = moves;
        this.vessels = vessels;
        this.visits = visits;
        this.yard = yard;
        this.properties = properties;
    }

    /** REPEATABLE READ so every query sees the same committed state. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public SlaSnapshot load(Instant now) {
        Instant windowStart = now.minus(properties.sla().craneProductivity().window());
        return new SlaSnapshot(
                now,
                cranes.findAll().stream()
                        .map(c -> new SlaSnapshot.Crane(c.id(), c.berthId(), c.assignedAt(), c.faulted(), c.faultCode(),
                                c.faultedSince()))
                        .toList(),
                moves.countByCraneBetween(windowStart, now),
                vessels.findByStatus("WAITING").stream()
                        .map(v -> new SlaSnapshot.WaitingVessel(v.id(), v.name(), v.arrivedAt()))
                        .toList(),
                visits.findOpen().stream()
                        .map(v -> new SlaSnapshot.OpenTruckVisit(v.id(), v.containerNumber(), v.direction(), v.lane(),
                                v.gateInAt()))
                        .toList(),
                yard.occupancy().stream()
                        .map(b -> new SlaSnapshot.Block(b.block(), b.used(), b.capacity()))
                        .toList());
    }
}
