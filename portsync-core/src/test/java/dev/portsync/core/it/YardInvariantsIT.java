package dev.portsync.core.it;

import dev.portsync.core.ingest.Outcome;
import dev.portsync.core.ops.ProcessingStats;
import dev.portsync.core.store.YardRepository;
import dev.portsync.events.ContainerNumber;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.yard.SlotRef;
import dev.portsync.events.yard.YardModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.portsync.core.it.Scenario.dropOff;
import static dev.portsync.core.it.Scenario.gateOut;
import static dev.portsync.core.it.Scenario.pickUp;
import static dev.portsync.core.it.Scenario.yardMove;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * Burst test: hundreds of events spread across all partitions and processed by three consumer
 * threads in parallel, including deliberate collisions, rehandles and a full replay of duplicates.
 * Afterwards the database must hold exactly the yard an independent in-memory model predicts, and
 * every invariant must hold.
 */
class YardInvariantsIT extends IntegrationTestBase {

    private static final int CONTAINERS = 150;

    @Autowired
    YardRepository yard;

    @Autowired
    ProcessingStats stats;

    @Test
    @DisplayName("yard invariants hold after a concurrent burst with collisions, rehandles and duplicates")
    void invariantsHoldAfterBurst() {
        YardModel model = new YardModel();
        List<String> boxes = new ArrayList<>();
        for (int i = 0; i < CONTAINERS; i++) {
            boxes.add(ContainerNumber.of("BRS", 'U', 100_000 + i));
        }

        // Phase 1: every container arrives at the gate (150 events over 3 partitions).
        List<UUID> wave = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            TerminalEvent e = dropOff(boxes.get(i), "TV-IN-" + i, at(0));
            send(e);
            wave.add(e.eventId());
        }
        awaitAll(wave);

        // Phase 2: stack them. Placements are sent in waves by tier so the expected result does
        // not depend on which partition happens to be consumed first; within a wave, 3 threads
        // write to different stacks concurrently.
        Map<Integer, List<TerminalEvent>> byTier = new LinkedHashMap<>();
        List<TerminalEvent> placements = new ArrayList<>();
        for (String box : boxes) {
            SlotRef slot = model.allocate(List.of("A", "B")).orElseThrow();
            model.place(box, slot);
            TerminalEvent e = yardMove(box, slot, at(1));
            byTier.computeIfAbsent(slot.tier(), t -> new ArrayList<>()).add(e);
            placements.add(e);
        }
        for (List<TerminalEvent> tierWave : byTier.values()) {
            tierWave.forEach(this::send);
            awaitAll(tierWave.stream().map(TerminalEvent::eventId).toList());
        }
        assertThat(placements).allSatisfy(e -> assertThat(outcomeOf(e.eventId())).isEqualTo("APPLIED"));

        // Phase 3: collisions. Two new boxes are reported into the same slot at the same moment; a third
        // reports a tier above empty ones; a fourth names a slot that does not exist.
        String racerA = ContainerNumber.of("RCE", 'U', 1);
        String racerB = ContainerNumber.of("RCE", 'U', 2);
        String floater = ContainerNumber.of("FLT", 'U', 3);
        String lost = ContainerNumber.of("LST", 'U', 4);
        sendAndAwait(dropOff(racerA, "TV-RA", at(2)));
        sendAndAwait(dropOff(racerB, "TV-RB", at(2)));
        sendAndAwait(dropOff(floater, "TV-FL", at(2)));
        sendAndAwait(dropOff(lost, "TV-LS", at(2)));
        SlotRef contested = model.allocate(List.of("A")).orElseThrow();
        SlotRef floating = new SlotRef("D", 6, 10, 3);
        TerminalEvent moveA = yardMove(racerA, contested, at(3));
        TerminalEvent moveB = yardMove(racerB, contested, at(3));
        TerminalEvent moveF = yardMove(floater, floating, at(3));
        TerminalEvent moveL = yardMove(lost, new SlotRef("E", 1, 1, 1), at(3));
        send(moveA);
        send(moveB);
        send(moveF);
        send(moveL);
        awaitAll(List.of(moveA.eventId(), moveB.eventId(), moveF.eventId(), moveL.eventId()));
        // Both racers are applied: whichever lands second goes on top of the first, never into it.
        assertThat(outcomeOf(moveA.eventId())).isEqualTo("APPLIED");
        assertThat(outcomeOf(moveB.eventId())).isEqualTo("APPLIED");
        SlotRef a = yard.locate(racerA).orElseThrow();
        SlotRef b = yard.locate(racerB).orElseThrow();
        assertThat(List.of(a.tier(), b.tier())).containsExactlyInAnyOrder(contested.tier(), contested.tier() + 1);
        model.place(a.tier() < b.tier() ? racerA : racerB, a.tier() < b.tier() ? a : b);
        model.place(a.tier() < b.tier() ? racerB : racerA, a.tier() < b.tier() ? b : a);
        // The "floating" report lands on the ground of its empty stack.
        assertThat(outcomeOf(moveF.eventId())).isEqualTo("APPLIED");
        assertThat(yard.locate(floater)).contains(floating.stack().tier(1));
        model.place(floater, floating.stack().tier(1));
        // A slot that does not exist is the one collision that is really impossible.
        assertThat(outcomeOf(moveL.eventId())).isEqualTo("REJECTED");

        // Phase 4: trucks collect 20 ground-tier containers from distinct stacks that have boxes on
        // top, forcing rehandles (everything above drops one tier).
        List<String> toCollect = model.containers().stream()
                .filter(c -> model.locate(c).orElseThrow().tier() == 1)
                .filter(c -> model.stack(model.locate(c).orElseThrow().stack()).get(1) != null)
                .sorted()
                .limit(20)
                .toList();
        assertThat(toCollect).hasSize(20);
        List<TerminalEvent> gateIns = new ArrayList<>();
        for (int i = 0; i < toCollect.size(); i++) {
            gateIns.add(pickUp(toCollect.get(i), "TV-OUT-" + i, at(10)));
        }
        gateIns.forEach(this::send);
        awaitAll(gateIns.stream().map(TerminalEvent::eventId).toList());
        List<TerminalEvent> gateOuts = new ArrayList<>();
        for (int i = 0; i < toCollect.size(); i++) {
            gateOuts.add(gateOut(toCollect.get(i), "TV-OUT-" + i, at(20)));
            model.remove(toCollect.get(i));
        }
        gateOuts.forEach(this::send);
        awaitAll(gateOuts.stream().map(TerminalEvent::eventId).toList());
        assertThat(gateOuts).allSatisfy(e -> assertThat(outcomeOf(e.eventId())).isEqualTo("APPLIED"));

        // Phase 5: replay every placement as an exact duplicate. Nothing may change.
        long duplicatesBefore = stats.count(Outcome.DUPLICATE);
        placements.forEach(this::send);
        await().atMost(Duration.ofSeconds(60)).until(() ->
                stats.count(Outcome.DUPLICATE) - duplicatesBefore >= placements.size());
        TerminalEvent marker = sendAndAwait(dropOff(ContainerNumber.of("MRK", 'U', 9), "TV-MRK", at(30)));

        // Verdict: no invariant violations, and the database yard is exactly the model's yard.
        assertThat(yard.invariantViolations()).isEmpty();
        assertThat(count("SELECT count(*) FROM yard_slots WHERE container_number IS NOT NULL"))
                .isEqualTo(model.occupied());
        for (String c : model.containers()) {
            assertThat(yard.locate(c)).as(c).contains(model.locate(c).orElseThrow());
        }
        for (String c : toCollect) {
            assertThat(yard.locate(c)).as(c).isEmpty();
        }
        assertThat(count("SELECT count(*) FROM container_history WHERE event_type = 'REHANDLE'")).isPositive();
        assertThat(outcomeOf(marker.eventId())).isEqualTo("APPLIED");

        given().get("/api/ops/invariants").then().statusCode(200).body("consistent", equalTo(true));
    }

    private void awaitAll(List<UUID> ids) {
        await().atMost(Duration.ofSeconds(60)).until(() ->
                jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id IN (:ids)")
                        .param("ids", ids).query(Long.class).single() == ids.size());
    }
}
