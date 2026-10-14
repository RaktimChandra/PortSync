package dev.portsync.events.yard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StackRulesTest {

    @Test
    void groundTierOfEmptyStackIsAllowed() {
        assertThat(StackRules.placementViolation(new String[4], 1)).isEmpty();
    }

    @Test
    void tierDirectlyOnTopIsAllowed() {
        assertThat(StackRules.placementViolation(new String[]{"A", "B", null, null}, 3)).isEmpty();
    }

    @Test
    void floatingTierIsRejected() {
        assertThat(StackRules.placementViolation(new String[]{"A", null, null, null}, 3))
                .hasValueSatisfying(reason -> assertThat(reason).contains("float"));
    }

    @Test
    void occupiedSlotIsRejected() {
        assertThat(StackRules.placementViolation(new String[]{"A", null, null, null}, 1))
                .hasValueSatisfying(reason -> assertThat(reason).contains("already holds A"));
    }

    @Test
    void tierOutsideRangeIsRejected() {
        assertThat(StackRules.placementViolation(new String[4], 0)).isPresent();
        assertThat(StackRules.placementViolation(new String[4], 5)).isPresent();
    }

    @Test
    void heightCountsContiguousTiers() {
        assertThat(StackRules.height(new String[4])).isZero();
        assertThat(StackRules.height(new String[]{"A", "B", "C", "D"})).isEqualTo(4);
    }

    @Test
    void gapDetection() {
        assertThat(StackRules.isGapFree(new String[]{"A", "B", null, null})).isTrue();
        assertThat(StackRules.isGapFree(new String[]{"A", null, "C", null})).isFalse();
    }

    @Test
    void removeAndSettleShiftsUpperTiersDown() {
        assertThat(StackRules.removeAndSettle(new String[]{"A", "B", "C", "D"}, 2))
                .containsExactly("A", "C", "D", null);
    }
}
