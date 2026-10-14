package dev.portsync.core.it;

import dev.portsync.core.dlq.DlqRepository;
import dev.portsync.events.ContainerNumber;
import dev.portsync.events.Topics;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.payload.CraneSignal;
import dev.portsync.events.yard.SlotRef;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.portsync.core.it.Scenario.arrived;
import static dev.portsync.core.it.Scenario.berthed;
import static dev.portsync.core.it.Scenario.craneStatus;
import static dev.portsync.core.it.Scenario.discharge;
import static dev.portsync.core.it.Scenario.dropOff;
import static dev.portsync.core.it.Scenario.gateOut;
import static dev.portsync.core.it.Scenario.yardMove;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DeadLetterIT extends IntegrationTestBase {

    private DlqRepository.DlqEntry awaitDeadLetter(UUID eventId) {
        return await().atMost(Duration.ofSeconds(30)).until(
                () -> dlq.recent(500).stream().filter(e -> eventId.equals(e.eventId())).findFirst(),
                Optional::isPresent).orElseThrow();
    }

    @Test
    @DisplayName("bad check digit -> INVALID_CHECK_DIGIT, nothing stored")
    void invalidCheckDigit() {
        String bad = ContainerNumber.withWrongCheckDigit("CSQU3054383");
        TerminalEvent event = sendAndAwait(dropOff(bad, "TV-BAD", at(0)));

        DlqRepository.DlqEntry entry = awaitDeadLetter(event.eventId());
        assertThat(entry.reason()).isEqualTo("INVALID_CHECK_DIGIT");
        assertThat(entry.detail()).contains("expected 3");
        assertThat(entry.sourceTopic()).isEqualTo(Topics.GATE);
        assertThat(count("SELECT count(*) FROM containers")).isZero();
        assertThat(count("SELECT count(*) FROM truck_visits")).isZero();
    }

    @Test
    @DisplayName("gate-out of a container not in the yard -> CONTAINER_NOT_IN_YARD")
    void gateOutNotInYard() {
        TerminalEvent event = sendAndAwait(gateOut("MSKU9070323", "TV-GHOST", at(0)));
        assertThat(awaitDeadLetter(event.eventId()).reason()).isEqualTo("CONTAINER_NOT_IN_YARD");
    }

    @Test
    @DisplayName("crane move on a faulted crane -> CRANE_FAULTED, and no partial state is left behind")
    void craneMoveOnFaultedCrane() {
        sendAndAwait(arrived("V-0201", at(0), 5, 0));
        sendAndAwait(berthed("V-0201", "B2", at(1), "QC03", "QC04"));
        sendAndAwait(craneStatus("QC03", CraneSignal.FAULT, at(2)));

        TerminalEvent move = sendAndAwait(discharge("TGHU7000015", "QC03", "V-0201", at(3)));

        assertThat(awaitDeadLetter(move.eventId()).reason()).isEqualTo("CRANE_FAULTED");
        assertThat(count("SELECT discharged FROM vessels WHERE id = 'V-0201'")).isZero();
        assertThat(count("SELECT count(*) FROM containers WHERE number = 'TGHU7000015'")).isZero();
        assertThat(count("SELECT count(*) FROM crane_moves")).isZero();
    }

    @Test
    @DisplayName("a slot outside the yard layout -> SLOT_INVALID")
    void slotOutsideLayout() {
        sendAndAwait(dropOff("HLXU8044100", "TV-OUT", at(0)));
        TerminalEvent move = sendAndAwait(yardMove("HLXU8044100", new SlotRef("C", 7, 4, 1), at(1)));
        assertThat(awaitDeadLetter(move.eventId()).reason()).isEqualTo("SLOT_INVALID");
        assertThat(count("SELECT count(*) FROM yard_slots WHERE container_number IS NOT NULL")).isZero();
    }

    @Test
    @DisplayName("a fifth container on a four-high stack -> STACK_FULL")
    void fullStack() {
        SlotRef.StackRef stack = new SlotRef.StackRef("D", 2, 2);
        for (int i = 1; i <= 5; i++) {
            sendAndAwait(dropOff(ContainerNumber.of("FUL", 'U', i), "TV-FULL-" + i, at(0)));
        }
        for (int i = 1; i <= 4; i++) {
            sendAndAwait(yardMove(ContainerNumber.of("FUL", 'U', i), stack.tier(i), at(1)));
        }
        TerminalEvent fifth = sendAndAwait(yardMove(ContainerNumber.of("FUL", 'U', 5), stack.tier(4), at(1)));
        assertThat(awaitDeadLetter(fifth.eventId()).reason()).isEqualTo("STACK_FULL");
        assertThat(count("SELECT count(*) FROM yard_slots WHERE container_number IS NOT NULL")).isEqualTo(4);
    }

    @Test
    @DisplayName("unreadable JSON -> MALFORMED_EVENT with the raw value kept, and the consumer keeps going")
    void malformedJsonDoesNotStopTheConsumer() {
        String garbage = "{this is not json";
        sendRaw(Topics.GATE, "garbage-key", garbage);

        DlqRepository.DlqEntry entry = await().atMost(Duration.ofSeconds(30)).until(
                () -> dlq.recent(500).stream().filter(e -> garbage.equals(e.rawValue())).findFirst(),
                Optional::isPresent).orElseThrow();
        assertThat(entry.reason()).isEqualTo("MALFORMED_EVENT");
        assertThat(entry.eventId()).isNull();

        TerminalEvent next = sendAndAwait(dropOff("CSQU3054383", "TV-AFTER", at(5)));
        assertThat(outcomeOf(next.eventId())).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("the dead-lettered record on terminal.dlq carries the original value and reason headers")
    void dlqRecordHasHeaders() {
        String bad = ContainerNumber.withWrongCheckDigit("MAEU1234567");
        TerminalEvent event = sendAndAwait(dropOff(bad, "TV-HDR", at(0)));
        awaitDeadLetter(event.eventId());

        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlq-inspector-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(Topics.DLQ));
            ConsumerRecord<String, String> found = await().pollInSameThread().atMost(Duration.ofSeconds(30)).until(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (r.value() != null && r.value().contains(event.eventId().toString())) {
                        return r;
                    }
                }
                return null;
            }, r -> r != null);
            assertThat(found.key()).isEqualTo(bad);
            assertThat(new String(found.headers().lastHeader(Topics.HEADER_DLQ_REASON).value(), StandardCharsets.UTF_8))
                    .isEqualTo("INVALID_CHECK_DIGIT");
            assertThat(new String(found.headers().lastHeader(Topics.HEADER_SOURCE_TOPIC).value(), StandardCharsets.UTF_8))
                    .isEqualTo(Topics.GATE);
        }
    }
}
