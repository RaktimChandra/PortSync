package dev.portsync.core.ingest;

import dev.portsync.core.clock.SimulatedClock;
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
 * Kafka entry point. One listener subscribes to the four operational topics; with three
 * partitions per topic and concurrency 3, three threads consume in parallel while each
 * partition (and therefore each entity) is still processed strictly in order.
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
    private final Timer processingTimer;
    private final Timer endToEndTimer;
    private final Counter malformedCounter;

    public TerminalEventListener(EventProcessor processor, DeadLetterPublisher deadLetters, SimulatedClock clock,
                                 ProcessingStats stats, MeterRegistry registry) {
        this.processor = processor;
        this.deadLetters = deadLetters;
        this.clock = clock;
        this.stats = stats;
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

    @KafkaListener(
            id = "terminal-events",
            topics = {Topics.GATE, Topics.YARD, Topics.BERTH, Topics.CRANE},
            concurrency = "${portsync.consumer.concurrency}")
    public void onRecord(ConsumerRecord<String, String> record) {
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

            ProcessingResult result = processor.process(event, meta);
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
}
