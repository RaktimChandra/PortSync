package dev.portsync.core.config;

import dev.portsync.core.ingest.RejectReason;
import dev.portsync.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

import java.nio.charset.StandardCharsets;

/**
 * Kafka wiring for the core service.
 *
 * <ul>
 *   <li>Creates the five topics with 3 partitions each (no-op if they exist).</li>
 *   <li>Listener error handling: business rejections never reach here (they are handled and
 *       dead-lettered by the processor). What does reach here is infrastructure trouble or a bug,
 *       so it is retried with exponential back-off; only after the retries are exhausted is the
 *       record sent to terminal.dlq with reason PROCESSING_FAILED, so one poison record cannot
 *       block its partition forever.</li>
 * </ul>
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public KafkaAdmin.NewTopics terminalTopics() {
        return new KafkaAdmin.NewTopics(Topics.ALL.stream()
                .map(name -> TopicBuilder.name(name).partitions(Topics.PARTITIONS).replicas(1).build())
                .toArray(NewTopic[]::new));
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, PortSyncProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(Topics.DLQ, -1));
        recoverer.setHeadersFunction((record, exception) -> {
            RecordHeaders headers = new RecordHeaders();
            headers.add(new RecordHeader(Topics.HEADER_DLQ_REASON,
                    RejectReason.PROCESSING_FAILED.name().getBytes(StandardCharsets.UTF_8)));
            Throwable root = exception.getCause() != null ? exception.getCause() : exception;
            headers.add(new RecordHeader(Topics.HEADER_DLQ_DETAIL,
                    ("Failed after retries: " + root.getClass().getSimpleName() + ": " + root.getMessage())
                            .getBytes(StandardCharsets.UTF_8)));
            headers.add(new RecordHeader(Topics.HEADER_SOURCE_TOPIC, record.topic().getBytes(StandardCharsets.UTF_8)));
            headers.add(new RecordHeader(Topics.HEADER_SOURCE_PARTITION,
                    Integer.toString(record.partition()).getBytes(StandardCharsets.UTF_8)));
            headers.add(new RecordHeader(Topics.HEADER_SOURCE_OFFSET,
                    Long.toString(record.offset()).getBytes(StandardCharsets.UTF_8)));
            return headers;
        });

        ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxInterval(5_000);
        backOff.setMaxAttempts(properties.consumer().maxRetries());

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(DeserializationException.class);
        handler.setRetryListeners((record, ex, attempt) -> {
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            log.warn("Retrying {}-{}@{} (attempt {}) after {}: {}", record.topic(), record.partition(),
                    record.offset(), attempt, root.getClass().getSimpleName(), root.getMessage());
        });
        return handler;
    }

    /**
     * Uses Boot's configurer (so spring.kafka.* properties and Micrometer client metrics apply)
     * and plugs in our error handler.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
