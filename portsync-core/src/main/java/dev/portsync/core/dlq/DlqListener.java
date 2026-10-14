package dev.portsync.core.dlq;

import com.fasterxml.jackson.databind.JsonNode;
import dev.portsync.core.ingest.Scope;
import dev.portsync.core.stream.LiveMessage;
import dev.portsync.core.stream.SseHub;
import dev.portsync.events.EventJson;
import dev.portsync.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Materialises {@code terminal.dlq} into the dlq_events table so the API and dashboard can show
 * rejected events with their reasons. Handles both our own rejections (portsync-* headers) and
 * records that exhausted their retries in the error handler (Spring's kafka_dlt-* headers).
 */
@Component
public class DlqListener {

    private static final Logger log = LoggerFactory.getLogger(DlqListener.class);

    private final DlqRepository repository;
    private final SseHub hub;

    public DlqListener(DlqRepository repository, SseHub hub) {
        this.repository = repository;
        this.hub = hub;
    }

    @KafkaListener(id = "dlq-materialiser", topics = Topics.DLQ, concurrency = "1")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        String reason = text(record, Topics.HEADER_DLQ_REASON);
        String detail = text(record, Topics.HEADER_DLQ_DETAIL);
        String sourceTopic = text(record, Topics.HEADER_SOURCE_TOPIC);
        Integer sourcePartition = parseInt(text(record, Topics.HEADER_SOURCE_PARTITION));
        Long sourceOffset = parseLong(text(record, Topics.HEADER_SOURCE_OFFSET));

        // Records recovered by DeadLetterPublishingRecoverer carry Spring's own headers instead.
        if (sourceTopic == null) {
            sourceTopic = text(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
            sourcePartition = intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
            sourceOffset = longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        }
        if (reason == null) {
            reason = "PROCESSING_FAILED";
        }
        if (detail == null || detail.isBlank()) {
            detail = text(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        }
        if (sourceTopic == null || sourcePartition == null || sourceOffset == null) {
            // Unknown producer wrote straight into the DLQ: identify it by its DLQ coordinates.
            sourceTopic = record.topic();
            sourcePartition = record.partition();
            sourceOffset = record.offset();
        }

        UUID eventId = null;
        String type = null;
        String entityId = record.key();
        try {
            JsonNode json = EventJson.mapper().readTree(record.value() == null ? "" : record.value());
            if (json != null && json.isObject()) {
                eventId = json.hasNonNull("eventId") ? UUID.fromString(json.get("eventId").asText()) : null;
                type = json.hasNonNull("type") ? json.get("type").asText() : null;
                entityId = json.hasNonNull("entityId") ? json.get("entityId").asText() : entityId;
            }
        } catch (Exception unreadable) {
            // Malformed payloads are exactly what the DLQ is for; keep the raw text.
        }

        DlqRepository.DlqEntry entry = new DlqRepository.DlqEntry(null, eventId, truncate(type, 20),
                truncate(entityId, 64), truncate(reason, 40), detail, record.value(), sourceTopic, sourcePartition,
                sourceOffset, record.partition(), record.offset(), null);
        if (repository.insert(entry)) {
            log.info("Dead-lettered {} {} reason={}", type, entityId, reason);
            hub.broadcast(new LiveMessage("dlq", new DlqMessage(eventId, type, entityId, reason, detail, sourceTopic)));
            hub.broadcast(new LiveMessage("state", new StateMessage(Set.of(Scope.CONTAINERS))));
        }
    }

    private static String text(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static Integer intHeader(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null || h.value() == null || h.value().length != 4 ? null : ByteBuffer.wrap(h.value()).getInt();
    }

    private static Long longHeader(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null || h.value() == null || h.value().length != 8 ? null : ByteBuffer.wrap(h.value()).getLong();
    }

    private static Integer parseInt(String s) {
        try {
            return s == null ? null : Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return s == null ? null : Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public record DlqMessage(UUID eventId, String type, String entityId, String reason, String detail, String sourceTopic) {
    }

    record StateMessage(Set<Scope> scopes) {
    }
}
