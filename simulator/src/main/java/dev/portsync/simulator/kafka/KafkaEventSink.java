package dev.portsync.simulator.kafka;

import dev.portsync.events.EventJson;
import dev.portsync.events.TerminalEvent;
import dev.portsync.simulator.engine.EventSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes events to their topic with the entity id as the record key. The producer is idempotent
 * with acks=all, so retries inside the client cannot reorder or duplicate records of one partition.
 */
@Component
public class KafkaEventSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventSink.class);

    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry registry;

    public KafkaEventSink(KafkaTemplate<String, String> kafka, MeterRegistry registry) {
        this.kafka = kafka;
        this.registry = registry;
    }

    @Override
    public void publish(TerminalEvent event) {
        send(event.topic(), event.partitionKey(), EventJson.write(event), event.type().name());
    }

    @Override
    public void publishRaw(String topic, String key, String value) {
        send(topic, key, value, "RAW");
    }

    private void send(String topic, String key, String value, String type) {
        kafka.send(topic, key, value).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Publish to {} failed for key {}: {}", topic, key, error.getMessage());
                Counter.builder("simulator.events.failed").tag("type", type).register(registry).increment();
            }
        });
        Counter.builder("simulator.events.published").description("Events handed to the Kafka producer")
                .tag("type", type).register(registry).increment();
    }
}
