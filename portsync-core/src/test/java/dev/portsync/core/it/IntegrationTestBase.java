package dev.portsync.core.it;

import dev.portsync.core.dlq.DlqRepository;
import dev.portsync.events.EventJson;
import dev.portsync.events.TerminalEvent;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

/**
 * Starts a real PostgreSQL 16 and a real Kafka broker (KRaft mode, no ZooKeeper) in Docker once
 * per JVM and runs the whole Spring Boot application against them. Every integration test talks to
 * the system the way production does: by producing records to Kafka and observing the database,
 * the DLQ topic and the HTTP API.
 *
 * <p>All IT classes share this exact configuration so Spring caches ONE application context: two
 * contexts would mean two consumers in the same group splitting the partitions between them.
 * {@code @AutoConfigureObservability} keeps the Prometheus endpoint on (Boot disables metric
 * exporters in tests by default).
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("portsync").withUsername("portsync").withPassword("portsync");

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static {
        // Singleton containers: started once, shared by every IT class, stopped by Testcontainers' reaper.
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Tests drive SLA evaluation explicitly with a chosen "now"; keep the scheduler out of the way.
        registry.add("portsync.sla.evaluation-interval", () -> "PT1H");
    }

    /** Base simulated time for test events; each test offsets from it. */
    protected static final Instant T0 = Instant.parse("2026-03-01T06:00:00Z");

    @Autowired
    protected KafkaTemplate<String, String> kafka;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected DlqRepository dlq;

    @LocalServerPort
    protected int port;

    @BeforeEach
    void resetState() {
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        jdbc.sql("""
                TRUNCATE processed_events, entity_versions, dlq_events, alerts, crane_moves, container_history,
                         truck_visits RESTART IDENTITY
                """).update();
        jdbc.sql("UPDATE yard_slots SET container_number = NULL, placed_at = NULL").update();
        jdbc.sql("DELETE FROM containers").update();
        jdbc.sql("UPDATE cranes SET berth_id = NULL, assigned_at = NULL, faulted = FALSE, fault_code = NULL, "
                + "faulted_since = NULL, last_heartbeat_at = NULL").update();
        jdbc.sql("UPDATE berths SET vessel_id = NULL").update();
        jdbc.sql("DELETE FROM vessels").update();
    }

    /** Publishes an event exactly like the simulator does: right topic, entityId as key. */
    protected void send(TerminalEvent event) {
        sendRaw(event.topic(), event.partitionKey(), EventJson.write(event));
    }

    protected void sendRaw(String topic, String key, String value) {
        try {
            kafka.send(topic, key, value).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish test record", e);
        }
    }

    protected TerminalEvent sendAndAwait(TerminalEvent event) {
        send(event);
        awaitProcessed(event.eventId());
        return event;
    }

    protected void awaitProcessed(UUID eventId) {
        await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id")
                        .param("id", eventId).query(Long.class).single() == 1);
    }

    protected String outcomeOf(UUID eventId) {
        return jdbc.sql("SELECT outcome FROM processed_events WHERE event_id = :id")
                .param("id", eventId).query(String.class).single();
    }

    protected long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    protected static Instant at(int minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }
}
