package dev.portsync.events.yard;

import dev.portsync.events.TerminalLayout;

import java.util.Optional;

/**
 * Physical stacking rules for a single stack, expressed over an array of occupants indexed by
 * tier (index 0 = tier 1, null = empty). Pure functions, used by both the simulator's in-memory
 * yard and the core service's database-backed yard so the rules are written exactly once.
 */
public final class StackRules {

    private StackRules() {
    }

    /**
     * Checks whether a container may be put down at {@code tier} in a stack.
     *
     * @return empty when allowed, otherwise the reason it is not
     */
    public static Optional<String> placementViolation(String[] stack, int tier) {
        if (stack.length != TerminalLayout.TIERS) {
            throw new IllegalArgumentException("Stack must have " + TerminalLayout.TIERS + " tiers");
        }
        if (tier < 1 || tier > TerminalLayout.TIERS) {
            return Optional.of("Tier " + tier + " is outside 1.." + TerminalLayout.TIERS);
        }
        if (stack[tier - 1] != null) {
            return Optional.of("Slot already holds " + stack[tier - 1]);
        }
        if (tier > 1 && stack[tier - 2] == null) {
            return Optional.of("Tier " + tier + " would float above empty tier " + (tier - 1));
        }
        return Optional.empty();
    }

    /** Number of occupied tiers, assuming the stack has no gaps. */
    public static int height(String[] stack) {
        int h = 0;
        while (h < stack.length && stack[h] != null) {
            h++;
        }
        return h;
    }

    /** True when every occupied tier sits on an occupied tier (or the ground). */
    public static boolean isGapFree(String[] stack) {
        boolean seenEmpty = false;
        for (String occupant : stack) {
            if (occupant == null) {
                seenEmpty = true;
            } else if (seenEmpty) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes the container at {@code tier} and lets everything above it drop one tier, which is
     * what a straddle carrier "rehandle" achieves physically. Returns the new stack contents.
     */
    public static String[] removeAndSettle(String[] stack, int tier) {
        String[] result = stack.clone();
        for (int t = tier - 1; t < result.length - 1; t++) {
            result[t] = result[t + 1];
        }
        result[result.length - 1] = null;
        return result;
    }
}
