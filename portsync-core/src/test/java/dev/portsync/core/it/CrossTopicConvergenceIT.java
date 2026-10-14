package dev.portsync.core.it;

import dev.portsync.core.store.YardRepository;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.yard.SlotRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static dev.portsync.core.it.Scenario.arrived;
import static dev.portsync.core.it.Scenario.berthed;
import static dev.portsync.core.it.Scenario.discharge;
import static dev.portsync.core.it.Scenario.dropOff;
import static dev.portsync.core.it.Scenario.gateOut;
import static dev.portsync.core.it.Scenario.pickUp;
import static dev.portsync.core.it.Scenario.yardMove;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A removal (GATE_OUT on terminal.gate) and a placement (YARD_MOVE on terminal.yard) on the same
 * stack travel on different topics, so the core can apply them in either order. Whatever the
 * order, the yard must end up in the same physical state and nothing may be dead-lettered.
 *
 * <p>This test exists because the end-to-end smoke test once showed false SLOT_OCCUPIED and
 * SLOT_FLOATING rejections under exactly this race, when placements were validated against the
 * reported tier.
 */
class CrossTopicConvergenceIT extends IntegrationTestBase {

    private static final SlotRef.StackRef STACK = new SlotRef.StackRef("B", 3, 3);

    @Autowired
    YardRepository yard;

    /** X sits alone on the stack and a truck is at the gate to collect it; Y is waiting to be stacked. */
    private void setUp(String x, String y) {
        sendAndAwait(dropOff(x, "TV-X-" + x, at(0)));
        sendAndAwait(yardMove(x, STACK.tier(1), at(1)));
        sendAndAwait(gateOut(x, "TV-X-" + x, at(2)));
        sendAndAwait(pickUp(x, "TV-P-" + x, at(10)));
        sendAndAwait(dropOff(y, "TV-Y-" + y, at(10)));
    }

    @Test
    @DisplayName("X leaves, then Y is stacked; the core sees Y first: Y lands on X, then drops to the ground")
    void placementAppliedBeforeEarlierRemoval() {
        String x = "CSQU3054383";
        String y = "MSKU9070323";
        setUp(x, y);

        // Reality: X left at 10:20, then Y was put on the now-empty stack at tier 1 at 10:25.
        TerminalEvent place = sendAndAwait(yardMove(y, STACK.tier(1), at(25)));
        TerminalEvent leave = sendAndAwait(gateOut(x, "TV-P-" + x, at(20)));

        assertThat(outcomeOf(place.eventId())).isEqualTo("APPLIED");
        assertThat(outcomeOf(leave.eventId())).isEqualTo("APPLIED");
        assertThat(yard.locate(y)).contains(STACK.tier(1));
        assertThat(yard.locate(x)).isEmpty();
        assertThat(yard.invariantViolations()).isEmpty();
        assertThat(dlq.recent(50)).isEmpty();
    }

    @Test
    @DisplayName("Y is stacked on X, then X is dug out; the core sees the removal first: Y lands on the ground")
    void removalAppliedBeforeEarlierPlacement() {
        String x = "MAEU1234567";
        String y = "TGHU7000015";
        setUp(x, y);

        // Reality: Y was put on top of X at tier 2 at 10:15, then X was dug out at 10:20 (Y dropped to tier 1).
        TerminalEvent leave = sendAndAwait(gateOut(x, "TV-P-" + x, at(20)));
        TerminalEvent place = sendAndAwait(yardMove(y, STACK.tier(2), at(15)));

        assertThat(outcomeOf(leave.eventId())).isEqualTo("APPLIED");
        assertThat(outcomeOf(place.eventId())).isEqualTo("APPLIED");
        assertThat(yard.locate(y)).contains(STACK.tier(1));
        assertThat(yard.invariantViolations()).isEmpty();
        assertThat(dlq.recent(50)).isEmpty();
        assertThat(jdbc.sql("SELECT description FROM container_history WHERE event_id = :id")
                .param("id", place.eventId()).query(String.class).single()).contains("reported tier 2");
    }

    @Test
    @DisplayName("a crane move consumed before the BERTH_ASSIGNED it depends on waits for it and is applied, not dead-lettered")
    void craneMoveWaitsForBerthAssignmentOnAnotherTopic() throws InterruptedException {
        String vessel = "V-9001";
        String container = "OOLU8650421";
        sendAndAwait(arrived(vessel, at(0), 1, 0));

        // The crane move travels on terminal.crane and the berth assignment on terminal.berth: the
        // core can see the move first. The listener must hold it back, not reject it.
        TerminalEvent move = discharge(container, "QC03", vessel, at(31));
        send(move);
        Thread.sleep(300);
        sendAndAwait(berthed(vessel, "B2", at(30), "QC03", "QC04"));
        awaitProcessed(move.eventId());

        assertThat(outcomeOf(move.eventId())).isEqualTo("APPLIED");
        assertThat(jdbc.sql("SELECT status FROM containers WHERE number = :n").param("n", container)
                .query(String.class).single()).isEqualTo("ON_QUAY");
        assertThat(dlq.recent(50)).isEmpty();
    }
}
