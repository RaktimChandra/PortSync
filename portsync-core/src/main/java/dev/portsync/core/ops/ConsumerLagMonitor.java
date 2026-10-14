package dev.portsync.core.ops;

import dev.portsync.core.config.PortSyncProperties;
import dev.portsync.events.Topics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumer lag = (latest offset in a partition) - (offset our group has committed). Measured from
 * the broker's point of view with the AdminClient every 10 s and exported per topic as
 * {@code portsync_consumer_lag}. Lag growing steadily is the first sign the core cannot keep up.
 */
@Component
public class ConsumerLagMonitor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsumerLagMonitor.class);

    private final AdminClient admin;
    private final String groupId;
    private final Map<String, AtomicLong> lagByTopic = new ConcurrentHashMap<>();
    private volatile Map<String, Long> lastSnapshot = Map.of();

    public ConsumerLagMonitor(KafkaAdmin kafkaAdmin, PortSyncProperties properties, MeterRegistry registry) {
        this.admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        this.groupId = properties.consumer().groupId();
        for (String topic : Topics.OPERATIONAL) {
            AtomicLong lag = new AtomicLong();
            lagByTopic.put(topic, lag);
            Gauge.builder("portsync.consumer.lag", lag, AtomicLong::get)
                    .description("Records not yet consumed by the core service, per topic")
                    .tag("topic", topic)
                    .register(registry);
        }
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void measure() {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> request = new HashMap<>();
            for (String topic : Topics.OPERATIONAL) {
                for (int p = 0; p < Topics.PARTITIONS; p++) {
                    request.put(new TopicPartition(topic, p), OffsetSpec.latest());
                }
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest =
                    admin.listOffsets(request).all().get(5, TimeUnit.SECONDS);
            Map<String, Long> totals = new HashMap<>();
            latest.forEach((tp, info) -> {
                OffsetAndMetadata c = committed.get(tp);
                long consumed = c == null ? 0 : c.offset();
                totals.merge(tp.topic(), Math.max(0, info.offset() - consumed), Long::sum);
            });
            totals.forEach((topic, lag) -> lagByTopic.get(topic).set(lag));
            lastSnapshot = Map.copyOf(totals);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Could not measure consumer lag: {}", e.getMessage());
        }
    }

    /** Latest measured lag per topic. */
    public Map<String, Long> lag() {
        return lastSnapshot;
    }

    @Override
    public void close() {
        admin.close();
    }
}
