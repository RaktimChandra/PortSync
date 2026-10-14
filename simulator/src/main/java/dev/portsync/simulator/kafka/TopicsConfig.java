package dev.portsync.simulator.kafka;

import dev.portsync.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Creates the terminal topics (3 partitions each) if they do not exist yet. */
@Configuration
public class TopicsConfig {

    @Bean
    public KafkaAdmin.NewTopics terminalTopics() {
        return new KafkaAdmin.NewTopics(Topics.ALL.stream()
                .map(name -> TopicBuilder.name(name).partitions(Topics.PARTITIONS).replicas(1).build())
                .toArray(NewTopic[]::new));
    }
}
