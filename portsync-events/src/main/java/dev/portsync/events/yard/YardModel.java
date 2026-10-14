package dev.portsync.events.yard;

import dev.portsync.events.TerminalLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory yard: which container sits in which slot. Enforces the yard invariants
 * (one container per slot, a container in at most one slot, nothing floating above an empty tier)
 * and implements the slot allocation policy.
 *
 * <p>Not thread-safe; the simulator drives it from a single scheduler thread.
 */
public class YardModel {

    /** A container that dropped down a tier because something below it was removed. */
    public record Rehandle(String container, SlotRef from, SlotRef to) {
    }

    private final Map<SlotRef.StackRef, String[]> stacks = new LinkedHashMap<>();
    private final Map<String, SlotRef> locations = new HashMap<>();

    public YardModel() {
        for (String block : TerminalLayout.BLOCKS) {
            for (int row = 1; row <= TerminalLayout.ROWS; row++) {
                for (int bay = 1; bay <= TerminalLayout.BAYS; bay++) {
                    stacks.put(new SlotRef.StackRef(block, row, bay), new String[TerminalLayout.TIERS]);
                }
            }
        }
    }

    /**
     * Picks a slot for a new container, trying blocks in the given order.
     *
     * <p>Policy: within a block choose the lowest stack (spreads containers out and keeps stacks
     * short, which reduces future rehandles); ties go to the lowest row, then lowest bay, so the
     * result is deterministic and easy to test. Always returns the tier directly on top of the
     * stack, so the result can never float.
     *
     * @return the slot, or empty when every preferred block is full
     */
    public Optional<SlotRef> allocate(List<String> blockPreference) {
        for (String block : blockPreference) {
            SlotRef.StackRef best = null;
            int bestHeight = Integer.MAX_VALUE;
            for (Map.Entry<SlotRef.StackRef, String[]> entry : stacks.entrySet()) {
                if (!entry.getKey().block().equals(block)) {
                    continue;
                }
                int h = StackRules.height(entry.getValue());
                if (h < TerminalLayout.TIERS && h < bestHeight) {
                    best = entry.getKey();
                    bestHeight = h;
                }
            }
            if (best != null) {
                return Optional.of(best.tier(bestHeight + 1));
            }
        }
        return Optional.empty();
    }

    /**
     * Puts a container down. If the container is already in the yard this is a yard-to-yard move:
     * it is lifted from its old slot first.
     *
     * @throws IllegalStateException if the move would break an invariant
     */
    public List<Rehandle> place(String container, SlotRef slot) {
        if (!slot.isWithinLayout()) {
            throw new IllegalStateException("Slot " + slot + " does not exist");
        }
        String[] target = stacks.get(slot.stack());
        List<Rehandle> rehandles = new ArrayList<>();
        SlotRef current = locations.get(container);
        if (current != null) {
            if (current.equals(slot)) {
                throw new IllegalStateException(container + " is already at " + slot);
            }
            String[] simulated = current.stack().equals(slot.stack())
                    ? StackRules.removeAndSettle(target, current.tier())
                    : target;
            StackRules.placementViolation(simulated, slot.tier()).ifPresent(reason -> {
                throw new IllegalStateException(reason);
            });
            rehandles.addAll(remove(container));
            target = stacks.get(slot.stack());
        }
        String[] stackNow = target;
        StackRules.placementViolation(stackNow, slot.tier()).ifPresent(reason -> {
            throw new IllegalStateException(reason);
        });
        stackNow[slot.tier() - 1] = container;
        locations.put(container, slot);
        return rehandles;
    }

    /**
     * Takes a container out of the yard. Containers above it drop one tier each.
     *
     * @return the rehandles caused, possibly empty
     * @throws IllegalStateException if the container is not in the yard
     */
    public List<Rehandle> remove(String container) {
        SlotRef slot = locations.remove(container);
        if (slot == null) {
            throw new IllegalStateException(container + " is not in the yard");
        }
        String[] before = stacks.get(slot.stack());
        String[] after = StackRules.removeAndSettle(before, slot.tier());
        List<Rehandle> rehandles = new ArrayList<>();
        for (int t = slot.tier(); t <= TerminalLayout.TIERS; t++) {
            String moved = after[t - 1];
            if (moved != null) {
                SlotRef from = slot.stack().tier(t + 1);
                SlotRef to = slot.stack().tier(t);
                locations.put(moved, to);
                rehandles.add(new Rehandle(moved, from, to));
            }
        }
        System.arraycopy(after, 0, before, 0, after.length);
        return rehandles;
    }

    public Optional<SlotRef> locate(String container) {
        return Optional.ofNullable(locations.get(container));
    }

    public boolean contains(String container) {
        return locations.containsKey(container);
    }

    public Optional<String> occupantOf(SlotRef slot) {
        String[] stack = stacks.get(slot.stack());
        return stack == null ? Optional.empty() : Optional.ofNullable(stack[slot.tier() - 1]);
    }

    public int occupied(String block) {
        return (int) locations.values().stream().filter(s -> s.block().equals(block)).count();
    }

    public int occupied() {
        return locations.size();
    }

    public double utilisation(String block) {
        return occupied(block) / (double) TerminalLayout.SLOTS_PER_BLOCK;
    }

    /** All containers currently in the yard (unordered snapshot). */
    public List<String> containers() {
        return List.copyOf(locations.keySet());
    }

    /** Read-only view of one stack, index 0 = tier 1. */
    public List<String> stack(SlotRef.StackRef stack) {
        String[] s = stacks.get(stack);
        if (s == null) {
            throw new IllegalArgumentException("No such stack " + stack);
        }
        return Collections.unmodifiableList(java.util.Arrays.asList(s.clone()));
    }

    /**
     * Verifies every invariant over the whole yard.
     *
     * @return human readable violations; empty when the yard is consistent
     */
    public List<String> invariantViolations() {
        List<String> violations = new ArrayList<>();
        Map<String, SlotRef> seen = new HashMap<>();
        for (Map.Entry<SlotRef.StackRef, String[]> entry : stacks.entrySet()) {
            String[] stack = entry.getValue();
            if (!StackRules.isGapFree(stack)) {
                violations.add("Floating container in stack " + entry.getKey());
            }
            for (int t = 1; t <= stack.length; t++) {
                String c = stack[t - 1];
                if (c == null) {
                    continue;
                }
                SlotRef here = entry.getKey().tier(t);
                SlotRef previous = seen.put(c, here);
                if (previous != null) {
                    violations.add(c + " is in two slots: " + previous + " and " + here);
                }
                if (!here.equals(locations.get(c))) {
                    violations.add(c + " location index disagrees with stack contents");
                }
            }
        }
        return violations;
    }
}
