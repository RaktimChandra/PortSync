package dev.portsync.core.ingest;

import dev.portsync.core.store.EntityVersionRepository;
import dev.portsync.core.store.ProcessedEventRepository;
import dev.portsync.events.TerminalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Applies one event to the database, exactly once, in a single transaction:
 *
 * <ol>
 *   <li><b>Idempotency</b> - claim the eventId in processed_events. Already there? It is a
 *       duplicate: return without touching anything.</li>
 *   <li><b>Out-of-order rule</b> - if the event happened <em>before</em> the last event applied to
 *       the same entity, record it as IGNORED_STALE and stop. Equal timestamps are applied.</li>
 *   <li><b>Apply</b> - run the handler inside a SAVEPOINT. A business rejection rolls back to the
 *       savepoint, so partial changes disappear but the processed_events row survives; the event is
 *       recorded as REJECTED and dead-lettered.</li>
 *   <li>Commit. Metrics and the live stream are notified only after the commit succeeded.</li>
 * </ol>
 */
@Service
public class EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    private final ProcessedEventRepository processed;
    private final EntityVersionRepository versions;
    private final EventRouter router;
    private final DeadLetterPublisher deadLetters;
    private final ApplicationEventPublisher afterCommit;
    private final TransactionTemplate savepoint;

    public EventProcessor(ProcessedEventRepository processed, EntityVersionRepository versions, EventRouter router,
                          DeadLetterPublisher deadLetters, ApplicationEventPublisher afterCommit,
                          PlatformTransactionManager transactionManager) {
        this.processed = processed;
        this.versions = versions;
        this.router = router;
        this.deadLetters = deadLetters;
        this.afterCommit = afterCommit;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Transactional
    public ProcessingResult process(TerminalEvent event, RecordMeta meta) {
        if (!processed.claim(event, meta)) {
            log.info("Duplicate event ignored");
            return publish(ProcessingResult.of(event, meta, Outcome.DUPLICATE, "DUPLICATE",
                    "eventId already processed", Set.of()));
        }

        Optional<Instant> lastApplied = versions.lastOccurredAt(event.type().entityKind(), event.entityId());
        if (lastApplied.isPresent() && event.occurredAt().isBefore(lastApplied.get())) {
            String detail = "Event time " + event.occurredAt() + " is older than the last applied event for "
                    + event.type().entityKind() + " " + event.entityId() + " at " + lastApplied.get();
            processed.finish(event.eventId(), Outcome.IGNORED_STALE, "STALE", detail);
            log.info("Stale event ignored: {}", detail);
            return publish(ProcessingResult.of(event, meta, Outcome.IGNORED_STALE, "STALE", detail, Set.of()));
        }

        try {
            Set<Scope> scopes = savepoint.execute(status -> router.apply(event));
            versions.advance(event.type().entityKind(), event.entityId(), event.occurredAt(), event.eventId());
            log.debug("Event applied");
            return publish(ProcessingResult.of(event, meta, Outcome.APPLIED, null, null, scopes));
        } catch (RejectedEventException rejection) {
            processed.finish(event.eventId(), Outcome.REJECTED, rejection.reason().name(), rejection.getMessage());
            deadLetters.publish(meta, rejection.reason(), rejection.getMessage());
            log.warn("Event rejected to DLQ: {} - {}", rejection.reason(), rejection.getMessage());
            return publish(ProcessingResult.of(event, meta, Outcome.REJECTED, rejection.reason().name(),
                    rejection.getMessage(), Set.of()));
        }
    }

    /** Queues the result for after-commit listeners (metrics, SSE) and returns it. */
    private ProcessingResult publish(ProcessingResult result) {
        afterCommit.publishEvent(result);
        return result;
    }
}
