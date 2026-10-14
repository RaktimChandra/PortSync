package dev.portsync.simulator.engine;

import dev.portsync.events.ContainerNumber;
import dev.portsync.events.EventType;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.TerminalLayout;
import dev.portsync.events.Topics;
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
import dev.portsync.events.yard.YardModel;
import dev.portsync.simulator.SimulatorProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * Discrete-event simulation of a container terminal.
 *
 * <p>Everything that happens is an {@link Action} on a single agenda ordered by simulated time.
 * {@link #tick(Instant)} runs every action that is due, in time order, so the events published for
 * any one entity always carry non-decreasing timestamps and are produced in the order they happen:
 * a container is gated in before it is stacked, stacked before it is loaded, and so on.
 *
 * <p>The simulation keeps its own {@link YardModel} using the same stacking rules as the core
 * service, so the slots it chooses are always valid. Fault injection methods deliberately bypass
 * those rules to produce invalid, duplicate or out-of-order events.
 *
 * <p>Not thread-safe by itself: all public methods are synchronized.
 */
public class TerminalSimulation {

    static final String SOURCE = "simulator";
    private static final String[] OWNERS = {"PSY", "OCN", "TRX", "MRL", "KXZ", "BLQ"};
    private static final String[] VESSEL_NAMES = {"Aurora Bay", "Borealis", "Cape Meridian", "Dawn Trader",
            "Eastern Star", "Falcon Crest", "Golden Tide", "Harbour Light", "Indigo Sea", "Jade Horizon",
            "Kestrel", "Lunar Wave", "Monsoon Spirit", "Northern Reach", "Ocean Pioneer", "Pacific Ember"};
    private static final String[] FAULT_CODES = {"SPREADER_JAM", "HOIST_OVERLOAD", "TROLLEY_DRIVE_FAULT",
            "PLC_COMM_LOSS", "WIND_SPEED_STOP"};
    /** Imports are stacked in A/B (near the gate), exports in C/D (near the quay). */
    private static final List<String> IMPORT_BLOCKS = List.of("A", "B");
    private static final List<String> EXPORT_BLOCKS = List.of("C", "D");
    private static final int RECENT_EVENTS = 200;

    private final SimulatorProperties props;
    private final EventSink sink;
    private final SplittableRandom random;

    private final YardModel yard = new YardModel();
    private final Map<String, QuayCrane> cranes = new LinkedHashMap<>();
    private final Map<String, VesselVisit> atBerth = new LinkedHashMap<>();
    private final Deque<VesselVisit> waiting = new ArrayDeque<>();
    private final List<String> importsAwaitingPickup = new ArrayList<>();
    private final List<String> exportsAwaitingLoad = new ArrayList<>();
    private final Set<String> onSite = new HashSet<>();
    private final Map<String, Instant> faultedSince = new HashMap<>();
    private final Map<String, Instant> lastContainerEvent = new HashMap<>();
    private final PriorityQueue<Action> agenda = new PriorityQueue<>(
            Comparator.comparing(Action::at).thenComparingLong(Action::seq));
    private final Deque<TerminalEvent> recent = new ArrayDeque<>();
    private final Map<EventType, Long> published = new EnumMap<>(EventType.class);

    private long actionSeq;
    private long vesselSeq;
    private long truckSeq;
    private boolean started;
    private Instant lastTick;

    /** A scheduled piece of work. {@code run} receives the simulated time it was scheduled for. */
    record Action(Instant at, long seq, String label, Consumer<Instant> run) {
    }

    public TerminalSimulation(SimulatorProperties props, EventSink sink) {
        this.props = props;
        this.sink = sink;
        this.random = new SplittableRandom(props.seed());
        for (String id : TerminalLayout.CRANES) {
            cranes.put(id, new QuayCrane(id));
        }
        for (String berth : TerminalLayout.BERTHS) {
            atBerth.put(berth, null);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Builds the opening inventory: containers that are already in the yard when the simulation
     * starts. Returned in three phases (gate-in, stacking, gate-out) that the caller publishes with
     * a pause in between, because they travel on different topics and Kafka only orders events
     * within one partition of one topic.
     */
    public synchronized List<List<TerminalEvent>> openingInventory(Instant start) {
        List<TerminalEvent> gateIns = new ArrayList<>();
        List<TerminalEvent> placements = new ArrayList<>();
        List<TerminalEvent> gateOuts = new ArrayList<>();
        Instant t = start.minus(Duration.ofMinutes(90));
        int n = 0;
        for (String block : TerminalLayout.BLOCKS) {
            double fill = props.initialFill().getOrDefault(block, 0.0);
            int count = (int) Math.round(Math.min(1.0, Math.max(0.0, fill)) * TerminalLayout.SLOTS_PER_BLOCK);
            for (int i = 0; i < count; i++) {
                Optional<SlotRef> slot = yard.allocate(List.of(block));
                if (slot.isEmpty()) {
                    break;
                }
                String box = newContainerNumber();
                String visit = nextTruckVisitId();
                Instant in = t.plusSeconds(n % 600);
                gateIns.add(event(EventType.GATE_IN, in, box,
                        new GateIn(visit, lane(), GateDirection.DROP_OFF, plate())));
                placements.add(event(EventType.YARD_MOVE, in.plus(Duration.ofMinutes(10)), box, YardMove.to(slot.get())));
                gateOuts.add(event(EventType.GATE_OUT, in.plus(Duration.ofMinutes(20)), box, new GateOut(visit, lane())));
                yard.place(box, slot.get());
                lastContainerEvent.put(box, gateOuts.get(gateOuts.size() - 1).occurredAt());
                if (block.equals("A") || block.equals("B")) {
                    importsAwaitingPickup.add(box);
                } else {
                    exportsAwaitingLoad.add(box);
                }
                n++;
            }
        }
        return List.of(gateIns, placements, gateOuts);
    }

    /** Schedules the recurring processes. Called once, at the simulated start time. */
    public synchronized void start(Instant start) {
        if (started) {
            return;
        }
        started = true;
        lastTick = start;
        for (QuayCrane crane : cranes.values()) {
            schedule(start.plusSeconds(random.nextInt(60)), "heartbeat " + crane.id, at -> heartbeat(crane, at));
            schedule(start.plus(Duration.ofMinutes(1)), "operate " + crane.id, at -> operate(crane, at));
            schedule(start.plus(Duration.ofMinutes(10)), "fault check " + crane.id, at -> randomFault(crane, at));
        }
        schedule(start.plus(Duration.ofMinutes(1)), "first vessel", this::vesselArrives);
        schedule(start.plus(Duration.ofMinutes(20)), "second vessel", this::vesselArrives);
        schedule(start.plus(Duration.ofMinutes(5)), "port control", this::portControl);
        schedule(start.plus(exponential(Duration.ofMinutes(3))), "truck", this::truckArrives);
        schedule(start.plus(nextVesselGap()), "vessel arrival", this::recurringVessel);
    }

    /** Runs every action due at or before {@code now}, in simulated-time order. */
    public synchronized int tick(Instant now) {
        if (!started) {
            start(now);
        }
        int executed = 0;
        while (!agenda.isEmpty() && !agenda.peek().at().isAfter(now)) {
            Action action = agenda.poll();
            action.run().accept(action.at());
            executed++;
        }
        lastTick = now;
        return executed;
    }

    // ------------------------------------------------------------------ vessels and berths

    private void recurringVessel(Instant at) {
        vesselArrives(at);
        schedule(at.plus(nextVesselGap()), "vessel arrival", this::recurringVessel);
    }

    private void vesselArrives(Instant at) {
        String id = String.format("V-%04d", ++vesselSeq);
        String name = VESSEL_NAMES[(int) ((vesselSeq - 1) % VESSEL_NAMES.length)];
        String voyage = String.format("%03d%s", 100 + random.nextInt(800), random.nextBoolean() ? "E" : "W");
        int discharge = between(props.minDischarge(), props.maxDischarge());
        int load = between(props.minLoad(), props.maxLoad());
        VesselVisit vessel = new VesselVisit(id, name, voyage, at, discharge, load);
        waiting.addLast(vessel);
        emit(event(EventType.VESSEL_ARRIVED, at, id, new VesselArrived(name, voyage, discharge, load)));
    }

    /** Every 5 minutes: berth waiting vessels where a berth and cranes are free. */
    private void portControl(Instant at) {
        for (Map.Entry<String, VesselVisit> berth : atBerth.entrySet()) {
            if (berth.getValue() != null || waiting.isEmpty()) {
                continue;
            }
            VesselVisit next = waiting.peekFirst();
            if (next.arrivedAt.plus(props.pilotageDelay()).isAfter(at)) {
                break;
            }
            List<String> crew = freeCranesFor(berth.getKey());
            if (crew.isEmpty()) {
                break;
            }
            waiting.pollFirst();
            next.berthId = berth.getKey();
            berth.setValue(next);
            for (String craneId : crew) {
                QuayCrane crane = cranes.get(craneId);
                crane.berthId = berth.getKey();
                crane.nextMoveAt = at.plus(Duration.ofMinutes(5));
            }
            emit(event(EventType.BERTH_ASSIGNED, at, next.id, new BerthAssigned(berth.getKey(), crew)));
        }
        schedule(at.plus(Duration.ofMinutes(5)), "port control", this::portControl);
    }

    /** Prefers the berth's two "home" cranes, falls back to any free healthy crane. */
    private List<String> freeCranesFor(String berthId) {
        int index = TerminalLayout.BERTHS.indexOf(berthId);
        List<String> preferred = TerminalLayout.CRANES.subList(index * 2, index * 2 + 2);
        List<String> crew = new ArrayList<>();
        for (String id : preferred) {
            QuayCrane c = cranes.get(id);
            if (c.berthId == null && !c.faulted) {
                crew.add(id);
            }
        }
        for (QuayCrane c : cranes.values()) {
            if (crew.size() >= TerminalLayout.CRANES_PER_BERTH) {
                break;
            }
            if (c.berthId == null && !c.faulted && !crew.contains(c.id)) {
                crew.add(c.id);
            }
        }
        return crew;
    }

    private void depart(VesselVisit vessel, Instant at) {
        if (atBerth.get(vessel.berthId) != vessel) {
            return;
        }
        emit(event(EventType.VESSEL_DEPARTED, at, vessel.id, new VesselDeparted(vessel.berthId)));
        for (QuayCrane crane : cranes.values()) {
            if (vessel.berthId.equals(crane.berthId)) {
                crane.berthId = null;
            }
        }
        atBerth.put(vessel.berthId, null);
    }

    // ------------------------------------------------------------------ cranes

    private void heartbeat(QuayCrane crane, Instant at) {
        emit(event(EventType.CRANE_STATUS, at, crane.id, new CraneStatus(CraneSignal.HEARTBEAT, null)));
        schedule(at.plus(props.heartbeatInterval()), "heartbeat " + crane.id, t -> heartbeat(crane, t));
    }

    /** One crane cycle: discharge first, then load, then let the vessel go. */
    private void operate(QuayCrane crane, Instant at) {
        Instant next = at.plus(Duration.ofMinutes(1));
        VesselVisit vessel = crane.berthId == null ? null : atBerth.get(crane.berthId);
        if (vessel != null && !crane.faulted) {
            if (!vessel.dischargeDone()) {
                String box = newContainerNumber();
                vessel.discharged++;
                emit(event(EventType.CRANE_MOVE, at, box, new CraneMove(crane.id, vessel.id, MoveKind.DISCHARGE)));
                Instant stackAt = at.plus(Duration.ofMinutes(2 + random.nextInt(4)));
                schedule(stackAt, "stack import " + box, t -> stack(box, null, true, t));
                next = at.plus(craneCycle(crane));
            } else if (!vessel.loadDone() && !exportsAwaitingLoad.isEmpty()) {
                String box = exportsAwaitingLoad.remove(random.nextInt(exportsAwaitingLoad.size()));
                yard.remove(box);
                onSite.remove(box);
                vessel.loaded++;
                emit(event(EventType.CRANE_MOVE, at, box, new CraneMove(crane.id, vessel.id, MoveKind.LOAD)));
                next = at.plus(craneCycle(crane));
            } else if ((vessel.loadDone() || exportsAwaitingLoad.isEmpty()) && !vessel.departureScheduled
                    && vessel.dischargeDone()) {
                // Work finished (or nothing left to load): lash, unmoor and sail.
                vessel.departureScheduled = true;
                schedule(at.plus(Duration.ofMinutes(15)), "depart " + vessel.id, t -> depart(vessel, t));
            }
        }
        crane.nextMoveAt = next;
        schedule(next, "operate " + crane.id, t -> operate(crane, t));
    }

    private void randomFault(QuayCrane crane, Instant at) {
        double probability = props.craneFaultsPerHour() * 10 / 60.0;
        if (!crane.faulted && crane.berthId != null && random.nextDouble() < probability) {
            fault(crane, FAULT_CODES[random.nextInt(FAULT_CODES.length)], at);
            Duration repair = Duration.ofMinutes(8 + random.nextInt(30));
            schedule(at.plus(repair), "repair " + crane.id, t -> recover(crane, t));
        }
        schedule(at.plus(Duration.ofMinutes(10)), "fault check " + crane.id, t -> randomFault(crane, t));
    }

    private void fault(QuayCrane crane, String code, Instant at) {
        crane.faulted = true;
        crane.faultCode = code;
        faultedSince.put(crane.id, at);
        emit(event(EventType.CRANE_STATUS, at, crane.id, new CraneStatus(CraneSignal.FAULT, code)));
    }

    private void recover(QuayCrane crane, Instant at) {
        if (!crane.faulted) {
            return;
        }
        crane.faulted = false;
        crane.faultCode = null;
        faultedSince.remove(crane.id);
        emit(event(EventType.CRANE_STATUS, at, crane.id, new CraneStatus(CraneSignal.RECOVERED, null)));
    }

    // ------------------------------------------------------------------ yard

    /**
     * Puts a container down. Without a forced block, the emptier of its two home blocks is tried
     * first, then the other pair, so blocks fill evenly as they would under a real yard planner.
     * Retries in 10 minutes if the whole yard is full.
     */
    private void stack(String box, String forcedBlock, boolean isImport, Instant at) {
        Optional<SlotRef> slot = yard.allocate(blockPreference(forcedBlock, isImport));
        if (slot.isEmpty()) {
            schedule(at.plus(Duration.ofMinutes(10)), "retry stack " + box, t -> stack(box, forcedBlock, isImport, t));
            return;
        }
        yard.place(box, slot.get());
        emit(event(EventType.YARD_MOVE, at, box, YardMove.to(slot.get())));
        if (isImport) {
            importsAwaitingPickup.add(box);
        } else {
            exportsAwaitingLoad.add(box);
        }
    }

    private List<String> blockPreference(String forcedBlock, boolean isImport) {
        List<String> home = isImport ? IMPORT_BLOCKS : EXPORT_BLOCKS;
        List<String> other = isImport ? EXPORT_BLOCKS : IMPORT_BLOCKS;
        List<String> order = new ArrayList<>();
        if (forcedBlock != null) {
            order.add(forcedBlock);
        }
        home.stream().sorted(Comparator.comparingInt(yard::occupied)).filter(b -> !order.contains(b)).forEach(order::add);
        other.stream().sorted(Comparator.comparingInt(yard::occupied)).filter(b -> !order.contains(b)).forEach(order::add);
        return order;
    }

    // ------------------------------------------------------------------ gate

    private void truckArrives(Instant at) {
        boolean pickup = !importsAwaitingPickup.isEmpty() && random.nextDouble() < props.pickupShare();
        if (pickup) {
            pickUp(at, normalTurn());
        } else {
            dropOff(at, null, Duration.ofMinutes(5 + random.nextInt(10)), normalTurn());
        }
        double perMinute = props.trucksPerHour() / 60.0;
        Duration gap = perMinute <= 0 ? Duration.ofHours(1) : exponential(Duration.ofSeconds((long) (60 / perMinute)));
        schedule(at.plus(gap), "truck", this::truckArrives);
    }

    private Duration normalTurn() {
        if (random.nextDouble() < props.slowTruckProbability()) {
            return Duration.ofMinutes(50 + random.nextInt(30));
        }
        return Duration.ofMinutes(15 + random.nextInt(25));
    }

    private void pickUp(Instant at, Duration turn) {
        String box = importsAwaitingPickup.remove(random.nextInt(importsAwaitingPickup.size()));
        String visit = nextTruckVisitId();
        String lane = lane();
        emit(event(EventType.GATE_IN, at, box, new GateIn(visit, lane, GateDirection.PICK_UP, plate())));
        schedule(at.plus(turn), "gate out " + visit, t -> {
            yard.remove(box);
            onSite.remove(box);
            emit(event(EventType.GATE_OUT, t, box, new GateOut(visit, lane())));
        });
    }

    private void dropOff(Instant at, String forcedBlock, Duration toStack, Duration turn) {
        String box = newContainerNumber();
        String visit = nextTruckVisitId();
        emit(event(EventType.GATE_IN, at, box, new GateIn(visit, lane(), GateDirection.DROP_OFF, plate())));
        schedule(at.plus(toStack), "stack export " + box, t -> stack(box, forcedBlock, false, t));
        Duration leave = turn.compareTo(toStack.plus(Duration.ofMinutes(2))) > 0 ? turn : toStack.plus(Duration.ofMinutes(3));
        schedule(at.plus(leave), "gate out " + visit, t ->
                emit(event(EventType.GATE_OUT, t, box, new GateOut(visit, lane()))));
    }

    // ------------------------------------------------------------------ fault injection

    /** What an injection published and what the core service is expected to do with it. */
    public record Injection(String description, String expectedOutcome, List<Published> events) {
    }

    public record Published(String eventId, String type, String entityId, Instant occurredAt) {

        static Published of(TerminalEvent e) {
            return new Published(e.eventId().toString(), e.type().name(), e.entityId(), e.occurredAt());
        }
    }

    public synchronized Injection faultCrane(String craneId, String code) {
        Instant at = injectionTime();
        QuayCrane crane = requireCrane(craneId);
        if (crane.faulted) {
            return new Injection(craneId + " is already faulted", "NONE", List.of());
        }
        fault(crane, code == null || code.isBlank() ? "OPERATOR_STOP" : code, at);
        return new Injection(craneId + " faulted; it will stay down until recovered. Expect CRANE_FAULT_DURATION "
                + "after the configured limit and lower crane productivity.", "APPLIED", List.of(Published.of(recent.peekLast())));
    }

    public synchronized Injection recoverCrane(String craneId) {
        Instant at = injectionTime();
        QuayCrane crane = requireCrane(craneId);
        if (!crane.faulted) {
            return new Injection(craneId + " is not faulted", "NONE", List.of());
        }
        recover(crane, at);
        return new Injection(craneId + " recovered; its fault alert will auto-resolve at the next SLA evaluation",
                "APPLIED", List.of(Published.of(recent.peekLast())));
    }

    /** Slows a crane down (e.g. a poor operator or a degraded drive) to trigger the productivity rule. */
    public synchronized Injection slowCrane(String craneId, double factor) {
        QuayCrane crane = requireCrane(craneId);
        if (factor <= 0 || factor > 1) {
            throw new IllegalArgumentException("factor must be in (0, 1]");
        }
        crane.speed = factor;
        return new Injection(craneId + " now works at " + Math.round(factor * 100) + "% of normal speed ("
                + Math.round(props.craneMovesPerHour() * factor) + " moves/h). Expect CRANE_LOW_PRODUCTIVITY once it "
                + "has worked a full window below the minimum; reset with factor=1.", "APPLIED", List.of());
    }

    /** Several vessels arrive together, so some must queue for a berth. */
    public synchronized Injection vesselSurge(int vessels) {
        Instant at = injectionTime();
        List<Published> sent = new ArrayList<>();
        for (int i = 0; i < vessels; i++) {
            vesselArrives(at);
            sent.add(Published.of(recent.peekLast()));
        }
        return new Injection(vessels + " vessels arrived at once. Those that cannot get a berth queue; expect "
                + "VESSEL_BERTH_WAIT for any that wait longer than the limit.", "APPLIED", sent);
    }

    /**
     * A burst of export drop-offs aimed at one block, with congested (long) turn times. By default
     * it targets the fullest block and sends enough trucks to push it to about 90%.
     */
    public synchronized Injection gateSurge(Integer requestedTrucks, String block) {
        Instant at = injectionTime();
        String target = block == null || block.isBlank()
                ? TerminalLayout.BLOCKS.stream().max(Comparator.comparingInt(yard::occupied)).orElse("D")
                : block.trim().toUpperCase();
        if (!TerminalLayout.isBlock(target)) {
            throw new IllegalArgumentException("Unknown block " + block);
        }
        int toNinety = (int) Math.ceil(0.9 * TerminalLayout.SLOTS_PER_BLOCK) - yard.occupied(target);
        int trucks = requestedTrucks != null ? requestedTrucks : Math.max(20, Math.min(120, toNinety));
        List<Published> sent = new ArrayList<>();
        for (int i = 0; i < trucks; i++) {
            Instant arrival = at.plusSeconds(i * 20L);
            dropOff(arrival, target, Duration.ofMinutes(2 + random.nextInt(5)),
                    Duration.ofMinutes(35 + random.nextInt(40)));
            sent.add(Published.of(recent.peekLast()));
        }
        return new Injection(trucks + " trucks dropping exports into block " + target + " with congested turn times. "
                + "Expect TRUCK_TURN_TIME alerts and, once the block passes the limit, YARD_BLOCK_UTILISATION.",
                "APPLIED", sent);
    }

    /** Re-publishes the most recent operational events with their original eventIds. */
    public synchronized Injection duplicates(int count) {
        List<TerminalEvent> candidates = new ArrayList<>(recent);
        List<TerminalEvent> chosen = candidates.subList(Math.max(0, candidates.size() - count), candidates.size());
        List<Published> sent = new ArrayList<>();
        for (TerminalEvent e : List.copyOf(chosen)) {
            sink.publish(e);
            sent.add(Published.of(e));
        }
        return new Injection("Re-sent " + sent.size() + " events with their original eventIds. The core must ignore "
                + "every copy: no state change, no DLQ entry.", "DUPLICATE", sent);
    }

    /**
     * Publishes a valid-looking event that happened in the past, after newer events for the same
     * entity. Prefers a faulted crane (a back-dated RECOVERED that must not clear the fault),
     * otherwise a back-dated YARD_MOVE that must not move a container.
     */
    public synchronized Injection outOfOrder() {
        Instant at = injectionTime();
        Optional<QuayCrane> faulted = cranes.values().stream().filter(c -> c.faulted).findFirst();
        if (faulted.isPresent()) {
            QuayCrane crane = faulted.get();
            Instant before = faultedSince.get(crane.id).minus(Duration.ofMinutes(5));
            TerminalEvent stale = event(EventType.CRANE_STATUS, before, crane.id, new CraneStatus(CraneSignal.RECOVERED, null));
            sink.publish(stale);
            return new Injection("Back-dated RECOVERED for faulted " + crane.id + " (" + before + ", before the fault). "
                    + "The crane must stay FAULTED.", "IGNORED_STALE", List.of(Published.of(stale)));
        }
        List<String> boxes = yard.containers();
        if (boxes.isEmpty()) {
            throw new IllegalStateException("Nothing in the yard to reorder yet");
        }
        String box = boxes.get(random.nextInt(boxes.size()));
        SlotRef elsewhere = yard.allocate(List.of("A", "B", "C", "D")).orElseThrow();
        Instant staleAt = lastContainerEvent.getOrDefault(box, at).minus(Duration.ofHours(1));
        TerminalEvent stale = event(EventType.YARD_MOVE, staleAt, box, YardMove.to(elsewhere));
        sink.publish(stale);
        return new Injection("Back-dated YARD_MOVE of " + box + " to " + elsewhere + " stamped " + staleAt
                + ", one hour before its latest event. The container must stay where it is.", "IGNORED_STALE",
                List.of(Published.of(stale)));
    }

    public synchronized Injection invalidContainer() {
        Instant at = injectionTime();
        String bad = ContainerNumber.withWrongCheckDigit(ContainerNumber.random(random, OWNERS));
        TerminalEvent e = event(EventType.GATE_IN, at, bad,
                new GateIn(nextTruckVisitId(), lane(), GateDirection.DROP_OFF, plate()));
        sink.publish(e);
        return new Injection("Gate-in of " + bad + " whose check digit is wrong", "REJECTED: INVALID_CHECK_DIGIT",
                List.of(Published.of(e)));
    }

    public enum ImpossibleKind {
        GATE_OUT_NOT_IN_YARD,
        MOVE_ON_FAULTED_CRANE,
        OUTSIDE_YARD,
        MALFORMED_JSON
    }

    public synchronized Injection impossible(ImpossibleKind kind) {
        Instant at = injectionTime();
        switch (kind) {
            case GATE_OUT_NOT_IN_YARD -> {
                String ghost = newContainerNumber();
                onSite.remove(ghost);
                TerminalEvent e = event(EventType.GATE_OUT, at, ghost, new GateOut(nextTruckVisitId(), lane()));
                sink.publish(e);
                return new Injection("Gate-out of " + ghost + ", which was never in the yard",
                        "REJECTED: CONTAINER_NOT_IN_YARD", List.of(Published.of(e)));
            }
            case MOVE_ON_FAULTED_CRANE -> {
                Optional<VesselVisit> vessel = atBerth.values().stream().filter(v -> v != null).findFirst();
                if (vessel.isEmpty()) {
                    throw new IllegalStateException("No vessel at berth yet; try again in a minute");
                }
                VesselVisit v = vessel.get();
                QuayCrane crane = cranes.values().stream().filter(c -> v.berthId.equals(c.berthId)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No crane at berth " + v.berthId));
                List<Published> sent = new ArrayList<>();
                boolean wasFaulted = crane.faulted;
                if (!wasFaulted) {
                    fault(crane, "INJECTED_FAULT", at);
                    sent.add(Published.of(recent.peekLast()));
                }
                String box = newContainerNumber();
                onSite.remove(box);
                TerminalEvent move = event(EventType.CRANE_MOVE, at.plusSeconds(10), box,
                        new CraneMove(crane.id, v.id, MoveKind.DISCHARGE));
                if (wasFaulted) {
                    sink.publish(move);
                } else {
                    // FAULT travels keyed by crane, the move keyed by container: different partitions.
                    // Send the move on the next tick so the fault is certainly applied first.
                    schedule(move.occurredAt(), "injected faulted move", t -> sink.publish(move));
                }
                sent.add(Published.of(move));
                return new Injection("Discharge by " + crane.id + " while it is faulted", "REJECTED: CRANE_FAULTED", sent);
            }
            case OUTSIDE_YARD -> {
                List<String> boxes = yard.containers();
                if (boxes.isEmpty()) {
                    throw new IllegalStateException("Nothing in the yard yet");
                }
                String box = boxes.get(random.nextInt(boxes.size()));
                SlotRef nowhere = new SlotRef("A", TerminalLayout.ROWS + 3, 1, 1);
                TerminalEvent e = event(EventType.YARD_MOVE, at, box, YardMove.to(nowhere));
                sink.publish(e);
                return new Injection("Move of " + box + " to row " + nowhere.row() + ", which does not exist",
                        "REJECTED: SLOT_INVALID", List.of(Published.of(e)));
            }
            case MALFORMED_JSON -> {
                String garbage = "{\"eventId\":\"not-a-uuid\",\"type\":\"GATE_IN\",\"payload\":";
                sink.publishRaw(Topics.GATE, "malformed", garbage);
                return new Injection("Unparseable record on " + Topics.GATE, "REJECTED: MALFORMED_EVENT", List.of());
            }
            default -> throw new IllegalArgumentException("Unknown kind " + kind);
        }
    }

    /**
     * Injected events are stamped with the simulated time the agenda has already reached, never
     * later: an event stamped "now" could otherwise be older than an agenda action still waiting
     * to run for the same crane, which would make our own next heartbeat look out of order.
     */
    private Instant injectionTime() {
        if (!started || lastTick == null) {
            throw new IllegalStateException("Simulation has not started yet");
        }
        return lastTick;
    }

    private QuayCrane requireCrane(String craneId) {
        QuayCrane crane = cranes.get(craneId == null ? "" : craneId.toUpperCase());
        if (crane == null) {
            throw new IllegalArgumentException("Unknown crane " + craneId + "; valid: " + TerminalLayout.CRANES);
        }
        return crane;
    }

    // ------------------------------------------------------------------ status

    public record Status(Instant simulatedTime, boolean started, int pendingActions, Map<String, Long> eventsPublished,
                         List<String> waitingVessels, Map<String, String> berths, Map<String, String> cranes,
                         Map<String, Integer> yardOccupancy, int importsAwaitingPickup, int exportsAwaitingLoad) {
    }

    public synchronized Status status() {
        Map<String, Long> counts = new LinkedHashMap<>();
        published.forEach((k, v) -> counts.put(k.name(), v));
        Map<String, String> berthView = new LinkedHashMap<>();
        atBerth.forEach((b, v) -> berthView.put(b, v == null ? "free"
                : v.id + " " + v.name + " (discharged " + v.discharged + "/" + v.dischargePlanned
                + ", loaded " + v.loaded + "/" + v.loadPlanned + ")"));
        Map<String, String> craneView = new LinkedHashMap<>();
        cranes.forEach((id, c) -> craneView.put(id, c.faulted ? "FAULTED " + c.faultCode
                : c.berthId == null ? "IDLE" : "WORKING at " + c.berthId));
        Map<String, Integer> occupancy = new LinkedHashMap<>();
        TerminalLayout.BLOCKS.forEach(b -> occupancy.put(b, yard.occupied(b)));
        return new Status(lastTick, started, agenda.size(), counts, waiting.stream().map(v -> v.id + " " + v.name).toList(),
                berthView, craneView, occupancy, importsAwaitingPickup.size(), exportsAwaitingLoad.size());
    }

    /** Yard view used by tests to cross-check consistency. */
    synchronized YardModel yard() {
        return yard;
    }

    // ------------------------------------------------------------------ helpers

    private void schedule(Instant at, String label, Consumer<Instant> run) {
        agenda.add(new Action(at, ++actionSeq, label, run));
    }

    private TerminalEvent event(EventType type, Instant at, String entityId, Object payload) {
        return TerminalEvent.of(type, at, entityId, SOURCE, payload);
    }

    private void emit(TerminalEvent e) {
        sink.publish(e);
        if (e.type().entityKind() == dev.portsync.events.EntityKind.CONTAINER) {
            lastContainerEvent.merge(e.entityId(), e.occurredAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
        published.merge(e.type(), 1L, Long::sum);
        if (e.type() != EventType.CRANE_STATUS || !e.payload().path("signal").asText().equals("HEARTBEAT")) {
            recent.addLast(e);
            if (recent.size() > RECENT_EVENTS) {
                recent.removeFirst();
            }
        }
    }

    private String newContainerNumber() {
        String number;
        do {
            number = ContainerNumber.random(random, OWNERS);
        } while (!onSite.add(number));
        return number;
    }

    private String nextTruckVisitId() {
        return String.format("TV-%06d", ++truckSeq);
    }

    private String lane() {
        return TerminalLayout.GATE_LANES.get(random.nextInt(TerminalLayout.GATE_LANES.size()));
    }

    private String plate() {
        return String.format("TN-%02d-%c%c-%04d", 1 + random.nextInt(99), (char) ('A' + random.nextInt(26)),
                (char) ('A' + random.nextInt(26)), random.nextInt(10_000));
    }

    private int between(int min, int max) {
        return max <= min ? min : min + random.nextInt(max - min + 1);
    }

    private Duration craneCycle(QuayCrane crane) {
        double meanSeconds = 3600.0 / (props.craneMovesPerHour() * crane.speed);
        double jitter = 0.7 + random.nextDouble() * 0.6; // +-30%
        return Duration.ofSeconds(Math.max(30, Math.round(meanSeconds * jitter)));
    }

    private Duration nextVesselGap() {
        return exponential(props.vesselInterarrival()).plus(Duration.ofMinutes(20));
    }

    private Duration exponential(Duration mean) {
        double u = random.nextDouble();
        double seconds = -mean.toSeconds() * Math.log(1 - u);
        return Duration.ofSeconds(Math.max(1, Math.round(seconds)));
    }
}
