package dev.portsync.events.yard;

import dev.portsync.events.ContainerNumber;
import dev.portsync.events.TerminalLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YardModelTest {

    private final YardModel yard = new YardModel();

    @Test
    @DisplayName("first allocation in an empty block is row 1, bay 1, tier 1")
    void firstAllocationIsGroundSlotOfFirstStack() {
        assertThat(yard.allocate(List.of("A"))).contains(new SlotRef("A", 1, 1, 1));
    }

    @Test
    @DisplayName("allocation spreads containers over the lowest stacks before stacking up")
    void allocationPrefersLowestStack() {
        SlotRef first = yard.allocate(List.of("B")).orElseThrow();
        yard.place("AAAU0000001", first);
        SlotRef second = yard.allocate(List.of("B")).orElseThrow();
        assertThat(second.tier()).isEqualTo(1);
        assertThat(second.stack()).isNotEqualTo(first.stack());
    }

    @Test
    @DisplayName("allocated slots never float and never collide, up to a full block")
    void fillingABlockKeepsInvariants() {
        List<SlotRef> used = new ArrayList<>();
        for (int i = 0; i < TerminalLayout.SLOTS_PER_BLOCK; i++) {
            SlotRef slot = yard.allocate(List.of("C")).orElseThrow();
            assertThat(used).doesNotContain(slot);
            yard.place(container(i), slot);
            used.add(slot);
        }
        assertThat(yard.occupied("C")).isEqualTo(TerminalLayout.SLOTS_PER_BLOCK);
        assertThat(yard.utilisation("C")).isEqualTo(1.0);
        assertThat(yard.invariantViolations()).isEmpty();
    }

    @Test
    void fullBlockFallsBackToNextPreferredBlock() {
        for (int i = 0; i < TerminalLayout.SLOTS_PER_BLOCK; i++) {
            yard.place(container(i), yard.allocate(List.of("D")).orElseThrow());
        }
        assertThat(yard.allocate(List.of("D"))).isEmpty();
        assertThat(yard.allocate(List.of("D", "A"))).map(SlotRef::block).contains("A");
    }

    @Test
    void cannotPlaceIntoOccupiedSlot() {
        SlotRef slot = new SlotRef("A", 2, 3, 1);
        yard.place("AAAU0000001", slot);
        assertThatThrownBy(() -> yard.place("AAAU0000002", slot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already holds");
    }

    @Test
    void cannotPlaceAboveAnEmptyTier() {
        assertThatThrownBy(() -> yard.place("AAAU0000001", new SlotRef("A", 1, 1, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("float");
    }

    @Test
    void cannotPlaceOutsideTheLayout() {
        assertThatThrownBy(() -> yard.place("AAAU0000001", new SlotRef("E", 1, 1, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> yard.place("AAAU0000001", new SlotRef("A", 7, 1, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("placing a container that is already in the yard moves it: it is never in two slots")
    void rePlacingMovesTheContainer() {
        yard.place("AAAU0000001", new SlotRef("A", 1, 1, 1));
        yard.place("AAAU0000001", new SlotRef("B", 1, 1, 1));
        assertThat(yard.locate("AAAU0000001")).contains(new SlotRef("B", 1, 1, 1));
        assertThat(yard.occupantOf(new SlotRef("A", 1, 1, 1))).isEmpty();
        assertThat(yard.invariantViolations()).isEmpty();
    }

    @Test
    @DisplayName("removing a container lets the containers above it drop one tier (rehandle)")
    void removalSettlesStack() {
        yard.place("AAAU0000001", new SlotRef("A", 1, 1, 1));
        yard.place("AAAU0000002", new SlotRef("A", 1, 1, 2));
        yard.place("AAAU0000003", new SlotRef("A", 1, 1, 3));

        List<YardModel.Rehandle> rehandles = yard.remove("AAAU0000001");

        assertThat(rehandles).containsExactly(
                new YardModel.Rehandle("AAAU0000002", new SlotRef("A", 1, 1, 2), new SlotRef("A", 1, 1, 1)),
                new YardModel.Rehandle("AAAU0000003", new SlotRef("A", 1, 1, 3), new SlotRef("A", 1, 1, 2)));
        assertThat(yard.stack(new SlotRef.StackRef("A", 1, 1)))
                .containsExactly("AAAU0000002", "AAAU0000003", null, null);
        assertThat(yard.invariantViolations()).isEmpty();
    }

    @Test
    void removingTopContainerCausesNoRehandle() {
        yard.place("AAAU0000001", new SlotRef("A", 1, 1, 1));
        yard.place("AAAU0000002", new SlotRef("A", 1, 1, 2));
        assertThat(yard.remove("AAAU0000002")).isEmpty();
        assertThat(yard.locate("AAAU0000001")).contains(new SlotRef("A", 1, 1, 1));
    }

    @Test
    void removingUnknownContainerFails() {
        assertThatThrownBy(() -> yard.remove("AAAU0000009")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("moving within the same stack to the top is allowed after the settle")
    void moveWithinSameStack() {
        yard.place("AAAU0000001", new SlotRef("A", 1, 1, 1));
        yard.place("AAAU0000002", new SlotRef("A", 1, 1, 2));
        yard.place("AAAU0000003", new SlotRef("A", 1, 1, 3));
        yard.place("AAAU0000001", new SlotRef("A", 1, 1, 3));
        assertThat(yard.stack(new SlotRef.StackRef("A", 1, 1)))
                .containsExactly("AAAU0000002", "AAAU0000003", "AAAU0000001", null);
        assertThat(yard.invariantViolations()).isEmpty();
    }

    @Test
    @DisplayName("randomised burst of places, moves and removals never breaks an invariant")
    void randomisedOperationsKeepInvariants() {
        SplittableRandom random = new SplittableRandom(2026);
        List<String> inYard = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            int op = random.nextInt(10);
            if (op < 5 || inYard.isEmpty()) {
                String c = container(10_000 + i);
                Optional<SlotRef> slot = yard.allocate(List.of(TerminalLayout.BLOCKS.get(random.nextInt(4))));
                if (slot.isPresent()) {
                    yard.place(c, slot.get());
                    inYard.add(c);
                }
            } else if (op < 8) {
                String c = inYard.remove(random.nextInt(inYard.size()));
                yard.remove(c);
            } else {
                String c = inYard.get(random.nextInt(inYard.size()));
                Optional<SlotRef> slot = yard.allocate(List.of(TerminalLayout.BLOCKS.get(random.nextInt(4))));
                if (slot.isPresent() && !slot.get().stack().equals(yard.locate(c).orElseThrow().stack())) {
                    yard.place(c, slot.get());
                }
            }
        }
        assertThat(yard.invariantViolations()).isEmpty();
        assertThat(yard.occupied()).isEqualTo(inYard.size());
    }

    private static String container(int serial) {
        return ContainerNumber.of("TST", 'U', serial);
    }
}
