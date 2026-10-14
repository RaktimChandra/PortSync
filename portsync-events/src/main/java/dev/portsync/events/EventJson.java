package dev.portsync.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The one JSON configuration used on the wire. Timestamps are ISO-8601 strings, unknown fields are
 * ignored so a newer producer can add optional fields without breaking an older consumer.
 */
public final class EventJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private EventJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(TerminalEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise event " + event.eventId(), e);
        }
    }

    /**
     * Parses a raw record value.
     *
     * @throws MalformedEventException when the JSON is unreadable or the envelope is incomplete
     */
    public static TerminalEvent read(String json) {
        TerminalEvent event;
        try {
            event = MAPPER.readValue(json, TerminalEvent.class);
        } catch (JsonProcessingException e) {
            throw new MalformedEventException("Unreadable JSON: " + e.getOriginalMessage(), e);
        }
        event.requireCompleteEnvelope();
        return event;
    }
}
