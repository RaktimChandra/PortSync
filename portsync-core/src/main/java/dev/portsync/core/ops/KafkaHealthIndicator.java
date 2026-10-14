package dev.portsync.core.ops;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Spring Boot has no built-in Kafka health check. This one asks the cluster to describe itself
 * with a short timeout. It is part of the readiness group: if Kafka is unreachable the instance
 * reports not-ready, but liveness stays UP, so an orchestrator stops routing traffic instead of
 * restart-looping a pod that cannot fix a broker outage.
 */
@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator, AutoCloseable {

    private final AdminClient admin;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }

    @Override
    public Health health() {
        try {
            DescribeClusterResult cluster = admin.describeCluster(new DescribeClusterOptions().timeoutMs(3_000));
            String clusterId = cluster.clusterId().get(3, TimeUnit.SECONDS);
            int brokers = cluster.nodes().get(3, TimeUnit.SECONDS).size();
            return Health.up().withDetail("clusterId", clusterId).withDetail("brokers", brokers).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down(e).build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage()).build();
        }
    }

    @Override
    public void close() {
        admin.close();
    }
}
