package dev.portsync.core.it;

import dev.portsync.core.ingest.Outcome;
import dev.portsync.core.ops.ProcessingStats;
import dev.portsync.events.TerminalEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static dev.portsync.core.it.Scenario.arrived;
import static dev.portsync.core.it.Scenario.berthed;
import static dev.portsync.core.it.Scenario.discharge;
import static dev.portsync.core.it.Scenario.dropOff;
import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyIT extends IntegrationTestBase {

    @Autowired
    ProcessingStats stats;

    @Test
    @DisplayName("the same CRANE_MOVE delivered five times is applied exactly once")
    void duplicateCraneMoveIsAppliedOnce() {
        sendAndAwait(arrived("V-0101", at(0), 10, 0));
        sendAndAwait(berthed("V-0101", "B1", at(1), "QC01", "QC02"));
        long duplicatesBefore = stats.count(Outcome.DUPLICATE);

        TerminalEvent move = discharge("CSQU3054383", "QC01", "V-0101", at(10));
        for (int i = 0; i < 5; i++) {
            send(move);
        }
        // Same topic and key as the duplicates, so once it is processed every copy before it has been too.
        TerminalEvent marker = discharge("CSQU3054383", "QC02", "V-0101", at(11));
        sendAndAwait(marker);

        assertThat(outcomeOf(move.eventId())).isEqualTo("APPLIED");
        assertThat(count("SELECT count(*) FROM crane_moves")).isEqualTo(1);
        assertThat(count("SELECT discharged FROM vessels WHERE id = 'V-0101'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM container_history WHERE event_type = 'CRANE_MOVE'")).isEqualTo(1);
        assertThat(stats.count(Outcome.DUPLICATE) - duplicatesBefore).isEqualTo(4);

        // The marker is a genuinely different event (new id): discharging a box already on the quay is rejected.
        assertThat(outcomeOf(marker.eventId())).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("a duplicated rejected event is dead-lettered only once")
    void duplicateRejectionIsDeadLetteredOnce() {
        TerminalEvent first = sendAndAwait(dropOff("MSKU9070323", "TV-1", at(0)));
        TerminalEvent conflicting = dropOff("MSKU9070323", "TV-2", at(1)); // already delivered -> rejected
        send(conflicting);
        send(conflicting);
        send(conflicting);
        TerminalEvent marker = sendAndAwait(dropOff("MSKU9070323", "TV-3", at(2)));

        assertThat(outcomeOf(first.eventId())).isEqualTo("APPLIED");
        assertThat(outcomeOf(conflicting.eventId())).isEqualTo("REJECTED");
        org.awaitility.Awaitility.await().untilAsserted(() ->
                assertThat(dlq.recent(50)).extracting(e -> e.eventId())
                        .contains(conflicting.eventId(), marker.eventId()));
        assertThat(dlq.recent(50)).filteredOn(e -> conflicting.eventId().equals(e.eventId())).hasSize(1);
        assertThat(count("SELECT count(*) FROM truck_visits")).isEqualTo(1);
    }
}
