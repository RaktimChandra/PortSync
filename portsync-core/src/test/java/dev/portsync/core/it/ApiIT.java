package dev.portsync.core.it;

import dev.portsync.events.yard.SlotRef;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dev.portsync.core.it.Scenario.dropOff;
import static dev.portsync.core.it.Scenario.yardMove;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/** REST Assured tests of the public API, including every error path and the consistent error body. */
class ApiIT extends IntegrationTestBase {

    @Test
    void overviewHasAllKpis() {
        when().get("/api/overview").then().statusCode(200).contentType(ContentType.JSON)
                .body("$", hasKey("simulatedTime"))
                .body("vesselsAtBerth", equalTo(0))
                .body("yardUtilisation", equalTo(0.0f))
                .body("blocks", hasSize(4))
                .body("openAlerts", equalTo(0))
                .body("$", hasKey("avgTruckTurnMinutes"));
    }

    @Test
    void berthsAndCranesReflectTheLayout() {
        when().get("/api/berths").then().statusCode(200)
                .body("id", equalTo(List.of("B1", "B2", "B3")))
                .body("cranes.flatten()", hasSize(0));
        when().get("/api/cranes").then().statusCode(200)
                .body("$", hasSize(6))
                .body("status", everyItem(equalTo("IDLE")));
    }

    @Test
    void yardBlockGrid() {
        when().get("/api/yard/blocks/A").then().statusCode(200)
                .body("block", equalTo("A"))
                .body("capacity", equalTo(240))
                .body("stacks", hasSize(60))
                .body("stacks[0].slots", hasSize(4));
        when().get("/api/yard/blocks/b").then().statusCode(200).body("block", equalTo("B"));
    }

    @Test
    @DisplayName("unknown block -> 404 with the standard error body")
    void unknownBlock() {
        when().get("/api/yard/blocks/Z").then().statusCode(404)
                .body("status", equalTo(404))
                .body("error", equalTo("Not Found"))
                .body("code", equalTo("BLOCK_NOT_FOUND"))
                .body("message", containsString("Z"))
                .body("path", equalTo("/api/yard/blocks/Z"))
                .body("timestamp", notNullValue());
    }

    @Test
    @DisplayName("container lookup: 400 for a bad check digit (with the expected digit), 404 for unknown, 200 with history")
    void containerLookup() {
        when().get("/api/containers/CSQU3054384").then().statusCode(400)
                .body("code", equalTo("INVALID_CONTAINER_NUMBER"))
                .body("details.expectedCheckDigit", equalTo(3));
        when().get("/api/containers/HELLO").then().statusCode(400).body("code", equalTo("INVALID_CONTAINER_NUMBER"));
        when().get("/api/containers/CSQU3054383").then().statusCode(404).body("code", equalTo("CONTAINER_NOT_FOUND"));

        sendAndAwait(dropOff("CSQU3054383", "TV-API", at(0)));
        sendAndAwait(yardMove("CSQU3054383", new SlotRef("C", 3, 7, 1), at(4)));

        when().get("/api/containers/csqu3054383").then().statusCode(200)
                .body("number", equalTo("CSQU3054383"))
                .body("status", equalTo("IN_YARD"))
                .body("location.label", equalTo("C-03-07-1"))
                .body("history.type", equalTo(List.of("GATE_IN", "YARD_MOVE")));
        when().get("/api/yard/blocks/C").then().body("used", equalTo(1));
    }

    @Test
    void validationEndpointAlwaysAnswers200() {
        when().get("/api/containers/CSQU3054384/validation").then().statusCode(200)
                .body("valid", equalTo(false)).body("expectedCheckDigit", equalTo(3));
        when().get("/api/containers/CSQU3054383/validation").then().statusCode(200).body("valid", equalTo(true));
    }

    @Test
    void alertEndpointsErrorPaths() {
        when().get("/api/alerts").then().statusCode(200).body("$", hasSize(0));
        when().get("/api/alerts?state=OPEN&state=ACKNOWLEDGED").then().statusCode(200);
        when().get("/api/alerts?state=BOGUS").then().statusCode(400).body("code", equalTo("INVALID_PARAMETER"));
        when().post("/api/alerts/999999/ack").then().statusCode(404).body("code", equalTo("ALERT_NOT_FOUND"));
        when().post("/api/alerts/999999/resolve").then().statusCode(404);
        when().post("/api/alerts/abc/ack").then().statusCode(400).body("code", equalTo("INVALID_PARAMETER"));
        when().get("/api/alerts/1/ack").then().statusCode(405).body("code", equalTo("METHOD_NOT_ALLOWED"));
    }

    @Test
    void eventsAndDeadLetters() {
        sendAndAwait(dropOff("MSKU9070323", "TV-EV", at(0)));
        when().get("/api/events?limit=10").then().statusCode(200)
                .body("type", hasItems("GATE_IN")).body("outcome", hasItems("APPLIED"));
        when().get("/api/dlq").then().statusCode(200);
        when().get("/api/events?limit=0").then().statusCode(400).body("code", equalTo("INVALID_PARAMETER"));
        when().get("/api/vessels?status=SUNK").then().statusCode(400);
        when().get("/api/vessels").then().statusCode(200);
        when().get("/api/gate/visits").then().statusCode(200).body("$", hasSize(1));
    }

    @Test
    void unknownRouteUsesTheSameErrorBody() {
        when().get("/api/does-not-exist").then().statusCode(404)
                .body("code", equalTo("NOT_FOUND")).body("path", equalTo("/api/does-not-exist"));
    }

    @Test
    @DisplayName("actuator: liveness, readiness (DB + Kafka) and custom Prometheus metrics")
    void observability() {
        when().get("/actuator/health/liveness").then().statusCode(200).body("status", equalTo("UP"));
        when().get("/actuator/health/readiness").then().statusCode(200).body("status", equalTo("UP"));
        when().get("/actuator/health").then().statusCode(200)
                .body("components.db.status", equalTo("UP"))
                .body("components.kafka.status", equalTo("UP"));
        sendAndAwait(dropOff("TGHU7000015", "TV-MET", at(0)));
        given().get("/actuator/prometheus").then().statusCode(200)
                .body(containsString("portsync_events_consumed_total"))
                .body(containsString("portsync_consumer_lag"))
                .body(containsString("portsync_alerts_active"))
                .body(containsString("portsync_event_latency_seconds"))
                .body(containsString("portsync_yard_invariant_violations"));
    }

    @Test
    void openApiDocumentListsTheEndpoints() {
        when().get("/v3/api-docs").then().statusCode(200)
                .body("paths", hasKey("/api/overview"))
                .body("paths", hasKey("/api/containers/{number}"))
                .body("paths", hasKey("/api/stream"));
    }

    @Test
    @DisplayName("SSE: a connected client receives hello, then a live 'event' message when an event is processed")
    void serverSentEvents() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/stream"))
                .header("Accept", "text/event-stream").build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("text/event-stream"));

        List<String> lines = new ArrayList<>();
        CompletableFuture<Boolean> sawEvent = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (lines) {
                        lines.add(line);
                    }
                    if (line.contains("TV-SSE") || line.contains("HLXU8044100")) {
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        });

        Thread.sleep(500);
        sendAndAwait(dropOff("HLXU8044100", "TV-SSE", at(0)));
        assertThat(sawEvent.get(20, TimeUnit.SECONDS)).isTrue();
        synchronized (lines) {
            assertThat(lines).anyMatch(l -> l.startsWith("event:hello"));
            assertThat(lines).anyMatch(l -> l.startsWith("event:event"));
        }
        response.body().close();
    }
}
