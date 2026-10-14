package dev.portsync.core.ingest.handler;

import dev.portsync.core.ingest.RejectReason;
import dev.portsync.core.ingest.RejectedEventException;
import dev.portsync.core.store.ContainerHistoryRepository;
import dev.portsync.core.store.YardRepository;
import dev.portsync.events.TerminalLayout;
import dev.portsync.events.yard.SlotRef;
import dev.portsync.events.yard.StackRules;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Database-backed yard operations that keep the invariants:
 * <ul>
 *   <li>one container per slot (primary key; placement always takes the first free tier)</li>
 *   <li>a container in at most one slot (unique index + move = remove then place)</li>
 *   <li>no container floating above an empty tier (placement goes on top; removal settles the stack)</li>
 * </ul>
 * Every method must run inside the caller's transaction; the stack row locks are held until commit.
 */
@Service
public class YardService {

    private static final Comparator<SlotRef.StackRef> LOCK_ORDER = Comparator
            .comparing(SlotRef.StackRef::block)
            .thenComparingInt(SlotRef.StackRef::row)
            .thenComparingInt(SlotRef.StackRef::bay);

    private final YardRepository yard;
    private final ContainerHistoryRepository history;

    public YardService(YardRepository yard, ContainerHistoryRepository history) {
        this.yard = yard;
        this.history = history;
    }

    /** Where a placement ended up, compared with the tier the event reported. */
    public record Placement(Optional<SlotRef> from, SlotRef slot, int reportedTier) {

        /** True when the container landed on a different tier than the event reported. */
        public boolean adjusted() {
            return slot.tier() != reportedTier;
        }
    }

    /**
     * Puts a container on top of the stack named by {@code requested}; if it is already elsewhere
     * in the yard it is moved (lifted first, so it is never in two slots).
     *
     * <p>The reported tier is not trusted blindly. A removal from the same stack may travel on
     * another topic (GATE_OUT on terminal.gate, LOAD on terminal.crane) and be applied before or
     * after this placement, so the stack can be one container taller or shorter here than it was
     * when the equipment reported the move. Physically a box can only be set down on top of a stack,
     * so that is where it goes; the history records any difference from the reported tier. This
     * makes the yard converge to the same state whichever order the topics are consumed in, and
     * nothing can ever float. A stack that is already {@value TerminalLayout#TIERS} high is rejected.
     */
    public Placement place(String container, SlotRef requested, Instant at, UUID eventId) {
        if (!requested.isWithinLayout()) {
            throw new RejectedEventException(RejectReason.SLOT_INVALID, "Slot " + requested
                    + " is outside the yard layout (blocks " + TerminalLayout.BLOCKS + ", rows 1-" + TerminalLayout.ROWS
                    + ", bays 1-" + TerminalLayout.BAYS + ", tiers 1-" + TerminalLayout.TIERS + ")");
        }
        Optional<SlotRef> current = yard.locate(container);

        // Lock every stack we touch, always in the same order, so concurrent transactions cannot deadlock.
        TreeSet<SlotRef.StackRef> toLock = new TreeSet<>(LOCK_ORDER);
        toLock.add(requested.stack());
        current.ifPresent(c -> toLock.add(c.stack()));
        Map<SlotRef.StackRef, String[]> locked = new LinkedHashMap<>();
        for (SlotRef.StackRef stack : toLock) {
            locked.put(stack, yard.lockStack(stack));
        }

        String[] target = locked.get(requested.stack());
        if (current.isPresent()) {
            SlotRef from = current.get();
            String[] source = locked.get(from.stack());
            if (!container.equals(source[from.tier() - 1])) {
                throw new IllegalStateException("Yard changed concurrently for " + container + "; will retry");
            }
            boolean sameStack = from.stack().equals(requested.stack());
            String[] targetAfterLift = sameStack ? StackRules.removeAndSettle(target, from.tier()) : target;
            requireRoom(container, targetAfterLift, requested);
            writeRemoval(container, from, source, at, eventId);
        } else {
            requireRoom(container, target, requested);
        }
        SlotRef slot = requested.stack().tier(StackRules.height(target) + 1);
        yard.setOccupant(slot, container, at);
        target[slot.tier() - 1] = container;
        return new Placement(current, slot, requested.tier());
    }

    private static void requireRoom(String container, String[] stack, SlotRef requested) {
        if (StackRules.height(stack) >= TerminalLayout.TIERS) {
            throw new RejectedEventException(RejectReason.STACK_FULL, "Cannot put " + container + " on stack "
                    + requested.stack().block() + "-" + requested.stack().row() + "-" + requested.stack().bay()
                    + ": it already holds " + TerminalLayout.TIERS + " containers");
        }
    }

    /**
     * Takes a container out of the yard (gate-out pick-up or vessel load). Containers stacked above
     * it are rehandled one tier down so nothing floats.
     *
     * @return the slot it was taken from
     */
    public SlotRef remove(String container, Instant at, UUID eventId) {
        SlotRef slot = yard.locate(container).orElseThrow(() ->
                new RejectedEventException(RejectReason.CONTAINER_NOT_IN_YARD, container + " is not in the yard"));
        String[] stack = yard.lockStack(slot.stack());
        if (!container.equals(stack[slot.tier() - 1])) {
            throw new IllegalStateException("Yard changed concurrently for " + container + "; will retry");
        }
        writeRemoval(container, slot, stack, at, eventId);
        return slot;
    }

    /**
     * Applies "remove and settle" to a locked stack and writes only the slots that changed.
     * Changed slots are cleared first and refilled second: the unique index on container_number is
     * checked per statement, so moving a container down in a single pass would briefly put it in
     * two slots and violate the index.
     */
    private void writeRemoval(String container, SlotRef slot, String[] stack, Instant at, UUID eventId) {
        String[] after = StackRules.removeAndSettle(stack, slot.tier());
        for (int tier = slot.tier(); tier <= TerminalLayout.TIERS; tier++) {
            if (changed(stack[tier - 1], after[tier - 1])) {
                yard.setOccupant(slot.stack().tier(tier), null, at);
            }
        }
        for (int tier = slot.tier(); tier <= TerminalLayout.TIERS; tier++) {
            String now = after[tier - 1];
            if (now == null || !changed(stack[tier - 1], now)) {
                continue;
            }
            SlotRef ref = slot.stack().tier(tier);
            yard.setOccupant(ref, now, at);
            history.add(now, eventId, "REHANDLE", at, "Rehandled from " + slot.stack().tier(tier + 1).label()
                    + " to " + ref.label() + " after " + container + " was taken out from below");
        }
        System.arraycopy(after, 0, stack, 0, after.length);
    }

    private static boolean changed(String before, String after) {
        return before == null ? after != null : !before.equals(after);
    }
}
