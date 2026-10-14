package dev.portsync.core.ingest.handler;

import dev.portsync.core.ingest.RejectReason;
import dev.portsync.core.ingest.RejectedEventException;
import dev.portsync.core.ingest.Scope;
import dev.portsync.core.store.BerthRepository;
import dev.portsync.core.store.CraneRepository;
import dev.portsync.core.store.CraneRow;
import dev.portsync.core.store.VesselRepository;
import dev.portsync.core.store.VesselRow;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.payload.BerthAssigned;
import dev.portsync.events.payload.VesselArrived;
import dev.portsync.events.payload.VesselDeparted;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** VESSEL_ARRIVED, BERTH_ASSIGNED, VESSEL_DEPARTED. */
@Component
public class VesselEventHandler {

    private final VesselRepository vessels;
    private final BerthRepository berths;
    private final CraneRepository cranes;

    public VesselEventHandler(VesselRepository vessels, BerthRepository berths, CraneRepository cranes) {
        this.vessels = vessels;
        this.berths = berths;
        this.cranes = cranes;
    }

    public Set<Scope> arrived(TerminalEvent event) {
        VesselArrived p = Checks.payload(event, VesselArrived.class);
        Checks.present(p.vesselName(), "vesselName");
        Checks.present(p.voyage(), "voyage");
        if (p.dischargeCount() < 0 || p.loadCount() < 0) {
            throw new RejectedEventException(RejectReason.MALFORMED_EVENT, "Planned move counts must not be negative");
        }
        vessels.lock(event.entityId()).ifPresent(existing -> {
            throw new RejectedEventException(RejectReason.VESSEL_STATE_CONFLICT,
                    "Vessel visit " + existing.id() + " already exists with status " + existing.status());
        });
        vessels.insertArrived(event.entityId(), p.vesselName(), p.voyage(), event.occurredAt(),
                p.dischargeCount(), p.loadCount());
        return EnumSet.of(Scope.VESSELS, Scope.BERTHS);
    }

    public Set<Scope> berthAssigned(TerminalEvent event) {
        BerthAssigned p = Checks.payload(event, BerthAssigned.class);
        Checks.berth(p.berthId());
        if (p.craneIds().isEmpty()) {
            throw new RejectedEventException(RejectReason.MALFORMED_EVENT, "At least one crane must be assigned");
        }
        if (new HashSet<>(p.craneIds()).size() != p.craneIds().size()) {
            throw new RejectedEventException(RejectReason.MALFORMED_EVENT, "Crane list contains duplicates: " + p.craneIds());
        }
        p.craneIds().forEach(Checks::crane);

        VesselRow vessel = vessels.lock(event.entityId()).orElseThrow(() ->
                new RejectedEventException(RejectReason.UNKNOWN_VESSEL, "Vessel visit " + event.entityId() + " never arrived"));
        if (!"WAITING".equals(vessel.status())) {
            throw new RejectedEventException(RejectReason.VESSEL_STATE_CONFLICT,
                    "Vessel " + vessel.id() + " is " + vessel.status() + ", only WAITING vessels can be berthed");
        }
        BerthRepository.BerthRow berth = berths.lock(p.berthId()).orElseThrow();
        if (berth.vesselId() != null) {
            throw new RejectedEventException(RejectReason.BERTH_OCCUPIED,
                    "Berth " + berth.id() + " is occupied by " + berth.vesselId());
        }
        List<CraneRow> locked = cranes.lockAll(p.craneIds());
        for (CraneRow crane : locked) {
            if (crane.berthId() != null) {
                throw new RejectedEventException(RejectReason.CRANE_UNAVAILABLE,
                        "Crane " + crane.id() + " is already working at berth " + crane.berthId());
            }
        }
        vessels.berth(vessel.id(), berth.id(), event.occurredAt());
        berths.setVessel(berth.id(), vessel.id());
        cranes.assign(p.craneIds(), berth.id(), event.occurredAt());
        return EnumSet.of(Scope.VESSELS, Scope.BERTHS, Scope.CRANES);
    }

    public Set<Scope> departed(TerminalEvent event) {
        VesselDeparted p = Checks.payload(event, VesselDeparted.class);
        Checks.berth(p.berthId());
        VesselRow vessel = vessels.lock(event.entityId()).orElseThrow(() ->
                new RejectedEventException(RejectReason.UNKNOWN_VESSEL, "Vessel visit " + event.entityId() + " never arrived"));
        if (!"AT_BERTH".equals(vessel.status()) || !p.berthId().equals(vessel.berthId())) {
            throw new RejectedEventException(RejectReason.VESSEL_STATE_CONFLICT,
                    "Vessel " + vessel.id() + " is " + vessel.status() + " at berth " + vessel.berthId()
                            + ", cannot depart from " + p.berthId());
        }
        berths.lock(p.berthId());
        vessels.depart(vessel.id(), event.occurredAt());
        berths.setVessel(p.berthId(), null);
        cranes.releaseBerth(p.berthId());
        return EnumSet.of(Scope.VESSELS, Scope.BERTHS, Scope.CRANES);
    }
}
