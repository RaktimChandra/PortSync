package dev.portsync.core.stream;

import dev.portsync.core.ingest.Outcome;
import dev.portsync.core.ingest.ProcessingResult;
import dev.portsync.core.ingest.Scope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Runs after the processing transaction COMMITS: counts the outcome and tells connected dashboards.
 * Because it is after-commit, a browser can never see a change that was later rolled back.
 */
@Component
public class LiveUpdates {

    private final SseHub hub;
    private final MeterRegistry registry;

    public LiveUpdates(SseHub hub, MeterRegistry registry) {
        this.hub = hub;
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProcessed(ProcessingResult result) {
        Counter.builder("portsync.events.consumed")
                .description("Events consumed, by type and outcome")
                .tag("type", result.type().name())
                .tag("outcome", result.outcome().name())
                .register(registry)
                .increment();
        if (result.outcome() == Outcome.REJECTED) {
            Counter.builder("portsync.dlq.events")
                    .description("Events dead-lettered, by reason")
                    .tag("reason", result.reason())
                    .register(registry)
                    .increment();
        }

        hub.broadcast(new LiveMessage("event", new EventMessage(result.eventId(), result.type().name(),
                result.entityId(), result.occurredAt(), result.outcome().name(), result.reason(), result.detail(),
                result.topic(), result.partition(), result.offset())));
        if (result.outcome() == Outcome.APPLIED && !result.scopes().isEmpty()) {
            hub.broadcast(new LiveMessage("state", new StateMessage(result.scopes())));
        }
    }

    /** Broadcasts that something outside event processing changed (alerts, DLQ). */
    public void stateChanged(Set<Scope> scopes) {
        hub.broadcast(new LiveMessage("state", new StateMessage(scopes)));
    }

    public record EventMessage(UUID eventId, String type, String entityId, Instant occurredAt, String outcome,
                               String reason, String detail, String topic, int partition, long offset) {
    }

    public record StateMessage(Set<Scope> scopes) {
    }
}
