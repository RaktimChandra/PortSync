package dev.portsync.core.api;

import dev.portsync.core.api.dto.Views;
import dev.portsync.core.dlq.DlqRepository;
import dev.portsync.core.store.ProcessedEventRepository;
import dev.portsync.core.stream.SseHub;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api")
@Tag(name = "Events", description = "Processed events, dead letters and the live stream")
public class EventController {

    private final ProcessedEventRepository processed;
    private final DlqRepository dlq;
    private final SseHub hub;

    public EventController(ProcessedEventRepository processed, DlqRepository dlq, SseHub hub) {
        this.processed = processed;
        this.dlq = dlq;
        this.hub = hub;
    }

    @GetMapping("/events")
    @Operation(summary = "Most recently processed events with their outcome (APPLIED, IGNORED_STALE, REJECTED)")
    public List<Views.EventItem> events(@RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return processed.recent(limit).stream()
                .map(e -> new Views.EventItem(e.eventId(), e.type(), e.entityId(), e.occurredAt(), e.outcome(),
                        e.reason(), e.detail(), e.topic(), e.partition(), e.offset(), e.processedAt()))
                .toList();
    }

    @GetMapping("/dlq")
    @Operation(summary = "Recently dead-lettered records with the reason they were rejected")
    public List<Views.DlqItem> deadLetters(@RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return dlq.recent(limit).stream()
                .map(d -> new Views.DlqItem(d.id(), d.eventId(), d.eventType(), d.entityId(), d.reason(), d.detail(),
                        d.sourceTopic(), d.sourcePartition(), d.sourceOffset(), d.deadLetteredAt(), d.rawValue()))
                .toList();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-Sent Events: 'event' (every processed event), 'state' (what to refresh), "
            + "'alert' (alert changes), 'dlq' (new dead letter)")
    public SseEmitter stream() {
        return hub.connect();
    }
}
