package dev.portsync.core.ingest;

import dev.portsync.core.clock.SimulatedClock;
import dev.portsync.core.config.PortSyncProperties;
import dev.portsync.core.ops.ProcessingStats;
import dev.portsync.events.EventJson;
import dev.portsync.events.MalformedEventException;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Kafka entry point.
 *
 * <p><b>One listener container per topic.</b> Each topic has three partitions and three consumer
 * threads, so every thread owns exactly one partition of one topic. That matters for the grace
 * period below: when a thread waits for an event on another topic, it only holds up its own
 * partition, never the partition that carries the event it is waiting for.
 *
 * <p><b>Grace period.</b> Some rejections are caused by ordering across topics, not by a bad event
 * (a crane move consumed a moment before the BERTH_ASSIGNED it depends on). For those reasons the
 * event is retried with exponential back-off (100 ms, 200 ms, ... about 5 s in total by default)
 * before it is finally dead-lettered. Blocking the partition preserves the order of that entity's
 * later events.
 *
 * <p>All four containers join the same consumer group ({@code portsync.consumer.group-id}); the
 * listener id only names the container (it would otherwise become the group id).
 *
 * <p>Every log line written while an event is processed carries its eventId, type and entityId
 * through the MDC, so the JSON logs can be filtered by event.
 */
@Component
public class TerminalEventListener {

    private static final Logger log = LoggerFactory.getLogger(TerminalEventListener.class);

    private final EventProcessor processor;
    private final DeadLetterPublisher deadLetters;
    private final SimulatedClock clock;
    private final ProcessingStats stats;
    private final MeterRegistry registry;
    private final int graceRetries;
    private final long graceBackoffMillis;
    private final Timer processingTimer;
    private final Timer endToEndTimer;
    private final Counter malformedCounter;

    public TerminalEventListener(EventProcessor processor, DeadLetterPublisher deadLetters, SimulatedClock clock,
                                 ProcessingStats stats, MeterRegistry registry, PortSyncProperties properties) {
        this.processor = processor;
        this.deadLetters = deadLetters;
        this.clock = clock;
        this.stats = stats;
        this.registry = registry;
        this.graceRetries = properties.consumer().graceRetries();
        this.graceBackoffMillis = properties.consumer().graceBackoff().toMillis();
        this.processingTimer = Timer.builder("portsync.event.processing")
                .description("Time to validate, apply and commit one event")
                .publishPercentileHistogram()
                .register(registry);
        this.endToEndTimer = Timer.builder("portsync.event.latency")
                .description("Kafka record timestamp to committed state change")
                .publishPercentileHistogram()
                .register(registry);
        this.malformedCounter = Counter.builder("portsync.dlq.events")
                .description("Events dead-lettered, by reason")
                .tag("reason", RejectReason.MALFORMED_EVENT.name())
                .register(registry);
    }

    @KafkaListener(id = "gate-events", groupId = "${portsync.consumer.group-id}", topics = Topics.GATE,
            concurrency = "${portsync.consumer.concurrency}")
    public void onGate(ConsumerRecord<String, String> record) {
        handle(record);
    }

    @KafkaListener(id = "yard-events", groupId = "${portsync.consumer.group-id}", topics = Topics.YARD,
            concurrency = "${portsync.consumer.concurrency}")
    public void onYard(ConsumerRecord<String, String> record) {
        handle(record);
    }

    @KafkaListener(id = "berth-events", groupId = "${portsync.consumer.group-id}", topics = Topics.BERTH,
            concurrency = "${portsync.consumer.concurrency}")
    public void onBerth(ConsumerRecord<String, String> record) {
        handle(record);
    }

    @KafkaListener(id = "crane-events", groupId = "${portsync.consumer.group-id}", topics = Topics.CRANE,
            concurrency = "${portsync.consumer.concurrency}")
    public void onCrane(ConsumerRecord<String, String> record) {
        handle(record);
    }

    void handle(ConsumerRecord<String, String> record) {
        RecordMeta meta = new RecordMeta(record.topic(), record.partition(), record.offset(), record.key(),
                record.value(), record.timestamp());
        MDC.put("topic", record.topic());
        MDC.put("partition", Integer.toString(record.partition()));
        MDC.put("offset", Long.toString(record.offset()));
        long started = System.nanoTime();
        try {
            TerminalEvent event;
            try {
                event = EventJson.read(record.value() == null ? "" : record.value());
            } catch (MalformedEventException e) {
                log.warn("Malformed record sent to DLQ: {}", e.getMessage());
                deadLetters.publish(meta, RejectReason.MALFORMED_EVENT, e.getMessage());
                stats.recordMalformed();
                malformedCounter.increment();
                return;
            }
            MDC.put("eventId", event.eventId().toString());
            MDC.put("eventType", event.type().name());
            MDC.put("entityId", event.entityId());

            ProcessingResult result = processWithGrace(event, meta);
            if (result.outcome() != Outcome.DUPLICATE) {
                clock.observe(event.occurredAt());
            }
            long processingNanos = System.nanoTime() - started;
            processingTimer.record(Duration.ofNanos(processingNanos));
            long latencyMillis = Math.max(0, System.currentTimeMillis() - record.timestamp());
            endToEndTimer.record(Duration.ofMillis(latencyMillis));
            stats.record(result.outcome(), latencyMillis * 1_000, processingNanos / 1_000);
        } finally {
            MDC.clear();
        }
    }

    private ProcessingResult processWithGrace(TerminalEvent event, RecordMeta meta) {
        for (int attempt = 0; ; attempt++) {
            boolean finalAttempt = attempt >= graceRetries;
            try {
                return processor.process(event, meta, finalAttempt);
            } catch (DeferredEventException deferred) {
                long wait = Math.min(2_000, graceBackoffMillis << attempt);
                Counter.builder("portsync.events.deferred")
                        .description("Events retried because a dependency on another topic was not applied yet")
                        .tag("reason", deferred.reason().name())
                        .register(registry)
                        .increment();
                log.info("Not applicable yet ({}: {}); retry {} of {} in {} ms", deferred.reason(),
                        deferred.getMessage(), attempt + 1, graceRetries, wait);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to retry", e);
                }
            }
        }
    }
}
