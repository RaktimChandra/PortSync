package dev.portsync.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.portsync.events.payload.BerthAssigned;
import dev.portsync.events.payload.CraneMove;
import dev.portsync.events.payload.CraneSignal;
import dev.portsync.events.payload.CraneStatus;
import dev.portsync.events.payload.GateDirection;
import dev.portsync.events.payload.GateIn;
import dev.portsync.events.payload.GateOut;
import dev.portsync.events.payload.MoveKind;
import dev.portsync.events.payload.VesselArrived;
import dev.portsync.events.payload.VesselDeparted;
import dev.portsync.events.payload.YardMove;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test: every event type the system can build serialises to JSON that satisfies the
 * published JSON Schema, and survives a round trip unchanged.
 */
class TerminalEventContractTest {

    private static final Instant T = Instant.parse("2026-10-14T08:00:00Z");
    private static JsonSchema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = TerminalEventContractTest.class.getResourceAsStream("/schema/terminal-event.schema.json")) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }
    }

    static Stream<TerminalEvent> oneOfEachType() {
        return Stream.of(
                TerminalEvent.of(EventType.VESSEL_ARRIVED, T, "V-0001", "test", new VesselArrived("Aurora", "042E", 30, 20)),
                TerminalEvent.of(EventType.BERTH_ASSIGNED, T, "V-0001", "test", new BerthAssigned("B1", List.of("QC01", "QC02"))),
                TerminalEvent.of(EventType.VESSEL_DEPARTED, T, "V-0001", "test", new VesselDeparted("B1")),
                TerminalEvent.of(EventType.CRANE_MOVE, T, "CSQU3054383", "test", new CraneMove("QC01", "V-0001", MoveKind.DISCHARGE)),
                TerminalEvent.of(EventType.CRANE_STATUS, T, "QC03", "test", new CraneStatus(CraneSignal.FAULT, "SPREADER_JAM")),
                TerminalEvent.of(EventType.CRANE_STATUS, T, "QC03", "test", new CraneStatus(CraneSignal.HEARTBEAT, null)),
                TerminalEvent.of(EventType.GATE_IN, T, "CSQU3054383", "test", new GateIn("TV-1", "G2", GateDirection.PICK_UP, "TN-01-AB-1234")),
                TerminalEvent.of(EventType.GATE_OUT, T, "CSQU3054383", "test", new GateOut("TV-1", "G2")),
                TerminalEvent.of(EventType.YARD_MOVE, T, "CSQU3054383", "test", new YardMove("C", 3, 7, 2)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oneOfEachType")
    @DisplayName("serialised event satisfies the JSON schema")
    void eventMatchesSchema(TerminalEvent event) {
        JsonNode json = EventJson.mapper().valueToTree(event);
        Set<ValidationMessage> errors = schema.validate(json);
        assertThat(errors).as(json.toString()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oneOfEachType")
    void roundTripPreservesEvent(TerminalEvent event) {
        TerminalEvent back = EventJson.read(EventJson.write(event));
        assertThat(back).isEqualTo(event);
        assertThat(back.topic()).isEqualTo(event.type().topic());
        assertThat(back.partitionKey()).isEqualTo(event.entityId());
    }

    @Test
    void typedPayloadIsRecovered() {
        TerminalEvent event = TerminalEvent.of(EventType.YARD_MOVE, T, "CSQU3054383", "test", new YardMove("C", 3, 7, 2));
        YardMove move = EventJson.read(EventJson.write(event)).payloadAs(YardMove.class);
        assertThat(move.slot().label()).isEqualTo("C-03-07-2");
    }

    @Test
    @DisplayName("schema rejects an envelope with a missing field and a crane id out of range")
    void schemaRejectsBadEvents() throws Exception {
        JsonNode missingEventId = EventJson.mapper().readTree("""
                {"schemaVersion":1,"type":"CRANE_STATUS","occurredAt":"2026-10-14T08:00:00Z",
                 "entityId":"QC01","source":"t","payload":{"signal":"FAULT"}}""");
        JsonNode badCrane = EventJson.mapper().readTree("""
                {"schemaVersion":1,"eventId":"6f1c2a3b-0000-4000-8000-000000000001","type":"CRANE_STATUS",
                 "occurredAt":"2026-10-14T08:00:00Z","entityId":"QC09","source":"t","payload":{"signal":"FAULT"}}""");
        assertThat(schema.validate(missingEventId)).isNotEmpty();
        assertThat(schema.validate(badCrane)).isNotEmpty();
    }

    @Test
    @DisplayName("unreadable JSON and incomplete envelopes are reported as malformed")
    void malformedInputIsDetected() {
        assertThatThrownBy(() -> EventJson.read("{not json")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> EventJson.read("{\"type\":\"GATE_IN\"}"))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> EventJson.read("""
                {"eventId":"6f1c2a3b-0000-4000-8000-000000000001","type":"TELEPORT","occurredAt":"2026-10-14T08:00:00Z",
                 "entityId":"x","payload":{}}"""))
                .isInstanceOf(MalformedEventException.class);
    }

    @Test
    void unknownFieldsAreIgnoredForForwardCompatibility() {
        TerminalEvent event = EventJson.read("""
                {"schemaVersion":1,"eventId":"6f1c2a3b-0000-4000-8000-000000000001","type":"CRANE_STATUS",
                 "occurredAt":"2026-10-14T08:00:00Z","entityId":"QC01","source":"t",
                 "payload":{"signal":"HEARTBEAT"},"traceId":"abc"}""");
        assertThat(event.payloadAs(CraneStatus.class).signal()).isEqualTo(CraneSignal.HEARTBEAT);
    }

    @Test
    void everyTypeRoutesToAnOperationalTopic() {
        for (EventType type : EventType.values()) {
            assertThat(Topics.OPERATIONAL).contains(type.topic());
        }
    }
}
