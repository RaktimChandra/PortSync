package dev.portsync.core.ingest;

import dev.portsync.events.Topics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends rejected records to {@code terminal.dlq}. The value is forwarded byte-for-byte so the
 * original event can be inspected or replayed; the reason and the source coordinates travel as
 * headers. The send is synchronous: if Kafka does not acknowledge it, an exception propagates, the
 * surrounding transaction rolls back and the record is retried, so a rejection is never lost.
 */
@Component
public class DeadLetterPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafka;

    public DeadLetterPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    public void publish(RecordMeta source, RejectReason reason, String detail) {
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.DLQ, null, source.key(), source.rawValue());
        record.headers()
                .add(header(Topics.HEADER_DLQ_REASON, reason.name()))
                .add(header(Topics.HEADER_DLQ_DETAIL, detail == null ? "" : detail))
                .add(header(Topics.HEADER_SOURCE_TOPIC, source.topic()))
                .add(header(Topics.HEADER_SOURCE_PARTITION, Integer.toString(source.partition())))
                .add(header(Topics.HEADER_SOURCE_OFFSET, Long.toString(source.offset())));
        try {
            kafka.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dead-lettering " + source, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not dead-letter " + source + ": " + e.getMessage(), e);
        }
    }

    private static RecordHeader header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
