package dev.portsync.events.yard;

import dev.portsync.events.TerminalLayout;

/**
 * Address of one yard slot: block (A..D), row (1..6), bay (1..10), tier (1..4, 1 = ground).
 * A "stack" is the column of tiers at one (block, row, bay).
 */
public record SlotRef(String block, int row, int bay, int tier) {

    /** True when the address exists in the terminal layout. */
    public boolean isWithinLayout() {
        return TerminalLayout.isBlock(block)
                && row >= 1 && row <= TerminalLayout.ROWS
                && bay >= 1 && bay <= TerminalLayout.BAYS
                && tier >= 1 && tier <= TerminalLayout.TIERS;
    }

    /** Identifies the stack this slot belongs to. */
    public StackRef stack() {
        return new StackRef(block, row, bay);
    }

    /** Compact human readable form, e.g. {@code C-03-07-2}. */
    public String label() {
        return String.format("%s-%02d-%02d-%d", block, row, bay, tier);
    }

    @Override
    public String toString() {
        return label();
    }

    /** Address of a stack (a slot without the tier). */
    public record StackRef(String block, int row, int bay) {

        public SlotRef tier(int tier) {
            return new SlotRef(block, row, bay, tier);
        }
    }
}
