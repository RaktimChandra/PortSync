package dev.portsync.core.api;

import dev.portsync.core.alert.AlertState;
import dev.portsync.core.api.dto.Views;
import dev.portsync.core.clock.SimulatedClock;
import dev.portsync.core.dlq.DlqRepository;
import dev.portsync.core.ops.ProcessingStats;
import dev.portsync.core.alert.AlertRepository;
import dev.portsync.core.store.BerthRepository;
import dev.portsync.core.store.ContainerHistoryRepository;
import dev.portsync.core.store.ContainerRepository;
import dev.portsync.core.store.ContainerRow;
import dev.portsync.core.store.CraneMoveRepository;
import dev.portsync.core.store.CraneRepository;
import dev.portsync.core.store.CraneRow;
import dev.portsync.core.store.TruckVisitRepository;
import dev.portsync.core.store.VesselRepository;
import dev.portsync.core.store.VesselRow;
import dev.portsync.core.store.YardRepository;
import dev.portsync.events.ContainerNumber;
import dev.portsync.events.TerminalLayout;
import dev.portsync.events.yard.SlotRef;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read side of the API: assembles views from the repositories. */
@Service
@Transactional(readOnly = true)
public class TerminalQueryService {

    private static final Duration HOUR = Duration.ofHours(1);

    private final SimulatedClock clock;
    private final BerthRepository berths;
    private final CraneRepository cranes;
    private final CraneMoveRepository moves;
    private final VesselRepository vessels;
    private final YardRepository yard;
    private final ContainerRepository containers;
    private final ContainerHistoryRepository history;
    private final TruckVisitRepository visits;
    private final AlertRepository alerts;
    private final DlqRepository dlq;
    private final ProcessingStats stats;

    public TerminalQueryService(SimulatedClock clock, BerthRepository berths, CraneRepository cranes,
                                CraneMoveRepository moves, VesselRepository vessels, YardRepository yard,
                                ContainerRepository containers, ContainerHistoryRepository history,
                                TruckVisitRepository visits, AlertRepository alerts, DlqRepository dlq,
                                ProcessingStats stats) {
        this.clock = clock;
        this.berths = berths;
        this.cranes = cranes;
        this.moves = moves;
        this.vessels = vessels;
        this.yard = yard;
        this.containers = containers;
        this.history = history;
        this.visits = visits;
        this.alerts = alerts;
        this.dlq = dlq;
        this.stats = stats;
    }

    public Views.Overview overview() {
        Instant now = clock.now();
        List<Views.BlockSummary> blocks = blockSummaries();
        int used = blocks.stream().mapToInt(Views.BlockSummary::used).sum();
        int capacity = blocks.stream().mapToInt(Views.BlockSummary::capacity).sum();
        long faulted = cranes.findAll().stream().filter(CraneRow::faulted).count();
        Double turn = visits.averageTurnMinutes(now.minus(HOUR), now);
        return new Views.Overview(
                now,
                clock.isSynchronised(),
                vessels.countByStatus("AT_BERTH"),
                vessels.countByStatus("WAITING"),
                moves.countBetween(now.minus(HOUR), now),
                capacity == 0 ? 0 : round(used / (double) capacity, 4),
                blocks,
                alerts.countByState(AlertState.OPEN),
                alerts.countByState(AlertState.ACKNOWLEDGED),
                turn == null ? null : round(turn, 1),
                visits.countOpen(),
                faulted,
                stats.total(),
                dlq.count());
    }

    public List<Views.Crane> cranes() {
        Instant now = clock.now();
        Map<String, Integer> lastHour = moves.countByCraneBetween(now.minus(HOUR), now);
        return cranes.findAll().stream().map(c -> toView(c, lastHour)).toList();
    }

    public List<Views.Berth> berths() {
        List<Views.Crane> craneViews = cranes();
        List<Views.Berth> result = new ArrayList<>();
        for (BerthRepository.BerthRow berth : berths.findAll()) {
            Views.Vessel vessel = berth.vesselId() == null ? null
                    : vessels.findById(berth.vesselId()).map(TerminalQueryService::toView).orElse(null);
            List<Views.Crane> atBerth = craneViews.stream().filter(c -> berth.id().equals(c.berthId())).toList();
            result.add(new Views.Berth(berth.id(), vessel, atBerth));
        }
        return result;
    }

    public List<Views.Vessel> vessels(Optional<String> status) {
        List<VesselRow> rows = status.map(s -> vessels.findByStatus(s.toUpperCase())).orElseGet(() -> vessels.findRecent(10));
        return rows.stream().map(TerminalQueryService::toView).toList();
    }

    public List<Views.BlockSummary> blockSummaries() {
        return yard.occupancy().stream()
                .map(b -> new Views.BlockSummary(b.block(), b.used(), b.capacity(), round(b.ratio(), 4)))
                .toList();
    }

    public Views.YardBlock block(String requested) {
        String block = requested == null ? "" : requested.trim().toUpperCase();
        if (!TerminalLayout.isBlock(block)) {
            throw ApiException.notFound("BLOCK_NOT_FOUND",
                    "Unknown yard block '" + requested + "'. Valid blocks: " + TerminalLayout.BLOCKS);
        }
        List<YardRepository.SlotRow> slots = yard.block(block);
        List<Views.Stack> stacks = new ArrayList<>();
        int used = 0;
        for (int row = 1; row <= TerminalLayout.ROWS; row++) {
            for (int bay = 1; bay <= TerminalLayout.BAYS; bay++) {
                final int r = row;
                final int b = bay;
                List<Views.Slot> stackSlots = slots.stream()
                        .filter(s -> s.row() == r && s.bay() == b)
                        .map(s -> new Views.Slot(s.tier(), s.containerNumber()))
                        .toList();
                int height = (int) stackSlots.stream().filter(s -> s.containerNumber() != null).count();
                used += height;
                stacks.add(new Views.Stack(row, bay, height, stackSlots));
            }
        }
        return new Views.YardBlock(block, TerminalLayout.ROWS, TerminalLayout.BAYS, TerminalLayout.TIERS,
                TerminalLayout.SLOTS_PER_BLOCK, used, round(used / (double) TerminalLayout.SLOTS_PER_BLOCK, 4), stacks);
    }

    /** Validates the number first (400 with the expected digit), then looks it up (404 if never seen). */
    public Views.Container container(String requested) {
        ContainerNumber.Validation v = ContainerNumber.validate(requested);
        if (!v.valid()) {
            throw ApiException.badRequest("INVALID_CONTAINER_NUMBER", v.reason(),
                    v.expectedCheckDigit() == null ? Map.of("input", String.valueOf(v.input()))
                            : Map.of("input", v.input(), "expectedCheckDigit", v.expectedCheckDigit()));
        }
        String number = v.input();
        List<Views.Rejection> rejections = dlq.forEntity(number).stream()
                .map(d -> new Views.Rejection(d.eventId(), d.eventType(), d.reason(), d.detail(), d.deadLetteredAt()))
                .toList();
        Optional<ContainerRow> row = containers.findByNumber(number);
        if (row.isEmpty()) {
            if (!rejections.isEmpty()) {
                return new Views.Container(number, true, "UNKNOWN", null, null, null, List.of(), rejections);
            }
            throw new ApiException(HttpStatus.NOT_FOUND, "CONTAINER_NOT_FOUND",
                    "Container " + number + " is valid but has never been seen at this terminal");
        }
        ContainerRow c = row.get();
        Views.Location location = yard.locate(number)
                .map(s -> new Views.Location(s.block(), s.row(), s.bay(), s.tier(), s.label()))
                .orElse(null);
        List<Views.HistoryItem> items = history.findByContainer(number).stream()
                .map(h -> new Views.HistoryItem(h.eventId(), h.eventType(), h.occurredAt(), h.description()))
                .toList();
        return new Views.Container(number, true, c.status(), c.vesselId(), location, c.lastEventAt(), items, rejections);
    }

    public List<Views.TruckVisit> openTruckVisits() {
        Instant now = clock.now();
        return visits.findOpen().stream()
                .map(v -> new Views.TruckVisit(v.id(), v.containerNumber(), v.direction(), v.lane(), v.truckPlate(),
                        v.gateInAt(), Math.max(0, Duration.between(v.gateInAt(), now).toMinutes())))
                .toList();
    }

    public Optional<SlotRef> locate(String number) {
        return yard.locate(number);
    }

    static Views.Crane toView(CraneRow c, Map<String, Integer> lastHour) {
        return new Views.Crane(c.id(), c.status(), c.berthId(), c.assignedAt(), c.faultCode(), c.faultedSince(),
                c.lastHeartbeatAt(), lastHour.getOrDefault(c.id(), 0));
    }

    static Views.Vessel toView(VesselRow v) {
        return new Views.Vessel(v.id(), v.name(), v.voyage(), v.status(), v.berthId(), v.arrivedAt(), v.berthedAt(),
                v.departedAt(), v.dischargePlanned(), v.discharged(), v.loadPlanned(), v.loaded());
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
