package dev.portsync.core.it;

import dev.portsync.events.TerminalEvent;
import dev.portsync.events.payload.CraneSignal;
import dev.portsync.events.yard.SlotRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static dev.portsync.core.it.Scenario.craneStatus;
import static dev.portsync.core.it.Scenario.dropOff;
import static dev.portsync.core.it.Scenario.yardMove;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule under test: an event whose occurredAt is EARLIER than the last event applied to the same
 * entity is recorded as IGNORED_STALE and does not change state. Equal timestamps are applied.
 */
class OutOfOrderIT extends IntegrationTestBase {

    @Test
    @DisplayName("a late RECOVERED that happened before the FAULT does not un-fault the crane")
    void staleCraneRecoveryIsIgnored() {
        TerminalEvent fault = sendAndAwait(craneStatus("QC03", CraneSignal.FAULT, at(20)));
        TerminalEvent lateRecovery = sendAndAwait(craneStatus("QC03", CraneSignal.RECOVERED, at(10)));

        assertThat(outcomeOf(fault.eventId())).isEqualTo("APPLIED");
        assertThat(outcomeOf(lateRecovery.eventId())).isEqualTo("IGNORED_STALE");
        assertThat(jdbc.sql("SELECT faulted FROM cranes WHERE id = 'QC03'").query(Boolean.class).single()).isTrue();

        TerminalEvent realRecovery = sendAndAwait(craneStatus("QC03", CraneSignal.RECOVERED, at(25)));
        assertThat(outcomeOf(realRecovery.eventId())).isEqualTo("APPLIED");
        assertThat(jdbc.sql("SELECT faulted FROM cranes WHERE id = 'QC03'").query(Boolean.class).single()).isFalse();
    }

    @Test
    @DisplayName("an event with exactly the same timestamp as the last applied one is applied")
    void equalTimestampIsApplied() {
        sendAndAwait(craneStatus("QC04", CraneSignal.FAULT, at(30)));
        TerminalEvent sameTime = sendAndAwait(craneStatus("QC04", CraneSignal.RECOVERED, at(30)));
        assertThat(outcomeOf(sameTime.eventId())).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("a stale YARD_MOVE does not move a container back to an old slot")
    void staleYardMoveIsIgnored() {
        String box = "MAEU1234567";
        SlotRef current = new SlotRef("A", 1, 1, 1);
        SlotRef old = new SlotRef("B", 2, 2, 1);
        sendAndAwait(dropOff(box, "TV-OOO-1", at(0)));
        sendAndAwait(yardMove(box, current, at(5)));
        TerminalEvent stale = sendAndAwait(yardMove(box, old, at(3)));

        assertThat(outcomeOf(stale.eventId())).isEqualTo("IGNORED_STALE");
        assertThat(jdbc.sql("SELECT block || '-' || row_no || '-' || bay_no || '-' || tier FROM yard_slots WHERE container_number = :n")
                .param("n", box).query(String.class).single()).isEqualTo("A-1-1-1");
        assertThat(jdbc.sql("SELECT reason FROM processed_events WHERE event_id = :id")
                .param("id", stale.eventId()).query(String.class).single()).isEqualTo("STALE");
        // Stale events are not errors: nothing is dead-lettered.
        assertThat(dlq.forEntity(box)).isEmpty();
    }
}
