package dev.portsync.core.it;

import dev.portsync.core.sla.SlaEvaluator;
import dev.portsync.core.sla.rules.BerthWaitRule;
import dev.portsync.core.sla.rules.CraneFaultRule;
import dev.portsync.core.sla.rules.CraneProductivityRule;
import dev.portsync.core.sla.rules.TruckTurnTimeRule;
import dev.portsync.core.sla.rules.YardUtilisationRule;
import dev.portsync.events.ContainerNumber;
import dev.portsync.events.payload.CraneSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static dev.portsync.core.it.Scenario.arrived;
import static dev.portsync.core.it.Scenario.berthed;
import static dev.portsync.core.it.Scenario.craneStatus;
import static dev.portsync.core.it.Scenario.dropOff;
import static dev.portsync.core.it.Scenario.gateOut;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * End to end: events in through Kafka, SLA evaluation at a chosen simulated time, alerts out
 * through the API, including de-duplication, auto-resolution and the manual lifecycle.
 */
class SlaAlertIT extends IntegrationTestBase {

    @Autowired
    SlaEvaluator evaluator;

    private long active(String rule, String entity) {
        return jdbc.sql("SELECT count(*) FROM alerts WHERE rule = :r AND entity_id = :e AND state <> 'RESOLVED'")
                .param("r", rule).param("e", entity).query(Long.class).single();
    }

    private long alertId(String rule, String entity) {
        return jdbc.sql("SELECT id FROM alerts WHERE rule = :r AND entity_id = :e AND state <> 'RESOLVED'")
                .param("r", rule).param("e", entity).query(Long.class).single();
    }

    @Test
    @DisplayName("crane fault: opens after 15 min, is de-duplicated, can be acknowledged, auto-resolves on recovery")
    void craneFaultLifecycle() {
        sendAndAwait(arrived("V-0301", at(0), 10, 10));
        sendAndAwait(berthed("V-0301", "B2", at(1), "QC03", "QC04"));
        sendAndAwait(craneStatus("QC03", CraneSignal.FAULT, at(10)));

        evaluator.evaluate(at(20)); // faulted 10 min: fine
        assertThat(active(CraneFaultRule.ID, "QC03")).isZero();

        evaluator.evaluate(at(30)); // 20 min > 15
        evaluator.evaluate(at(31));
        evaluator.evaluate(at(32));
        assertThat(active(CraneFaultRule.ID, "QC03")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM alerts WHERE rule = 'CRANE_FAULT_DURATION'")).isEqualTo(1);

        long id = alertId(CraneFaultRule.ID, "QC03");
        given().post("/api/alerts/{id}/ack", id).then().statusCode(200)
                .body("state", equalTo("ACKNOWLEDGED")).body("acknowledgedAt", notNullValue());
        given().post("/api/alerts/{id}/ack", id).then().statusCode(409)
                .body("code", equalTo("ALERT_STATE_CONFLICT"));

        sendAndAwait(craneStatus("QC03", CraneSignal.RECOVERED, at(40)));
        evaluator.evaluate(at(41));
        assertThat(active(CraneFaultRule.ID, "QC03")).isZero();
        assertThat(jdbc.sql("SELECT resolution FROM alerts WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("AUTO");
    }

    @Test
    @DisplayName("low productivity: manual resolve cannot hide a breach that is still happening")
    void productivityAndManualResolve() {
        sendAndAwait(arrived("V-0302", at(0), 10, 10));
        sendAndAwait(berthed("V-0302", "B1", at(1), "QC01", "QC02"));

        evaluator.evaluate(at(30)); // assigned < 60 min ago: not judged yet
        assertThat(active(CraneProductivityRule.ID, "QC01")).isZero();

        evaluator.evaluate(at(70)); // a full hour with zero moves
        assertThat(active(CraneProductivityRule.ID, "QC01")).isEqualTo(1);
        assertThat(active(CraneProductivityRule.ID, "QC02")).isEqualTo(1);

        long first = alertId(CraneProductivityRule.ID, "QC01");
        given().post("/api/alerts/{id}/resolve", first).then().statusCode(200).body("state", equalTo("RESOLVED"))
                .body("resolution", equalTo("MANUAL"));
        given().post("/api/alerts/{id}/resolve", first).then().statusCode(409);

        evaluator.evaluate(at(71));
        long second = alertId(CraneProductivityRule.ID, "QC01");
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("vessel waiting too long for a berth raises an alert that clears when it berths")
    void berthWait() {
        sendAndAwait(arrived("V-0303", at(0), 1, 1));
        evaluator.evaluate(at(60));
        assertThat(active(BerthWaitRule.ID, "V-0303")).isZero();
        evaluator.evaluate(at(95));
        assertThat(active(BerthWaitRule.ID, "V-0303")).isEqualTo(1);

        sendAndAwait(berthed("V-0303", "B3", at(96), "QC05", "QC06"));
        evaluator.evaluate(at(97));
        assertThat(active(BerthWaitRule.ID, "V-0303")).isZero();
    }

    @Test
    @DisplayName("truck turn time alert opens while the truck is inside and auto-resolves at gate-out")
    void truckTurnTime() {
        sendAndAwait(dropOff("CSQU3054383", "TV-SLOW", at(0)));
        evaluator.evaluate(at(46));
        assertThat(active(TruckTurnTimeRule.ID, "TV-SLOW")).isEqualTo(1);

        sendAndAwait(gateOut("CSQU3054383", "TV-SLOW", at(50)));
        evaluator.evaluate(at(51));
        assertThat(active(TruckTurnTimeRule.ID, "TV-SLOW")).isZero();
    }

    @Test
    @DisplayName("yard block above 85% raises a utilisation alert")
    void yardUtilisation() {
        // Fill block D to 210/240 = 87.5% directly: this test is about the rule and alert pipeline.
        jdbc.sql("""
                INSERT INTO containers (number, status, last_event_at)
                SELECT 'FIL' || 'U' || lpad(g::text, 6, '0') || '0', 'IN_YARD', now()
                FROM generate_series(1, 210) g
                """).update();
        jdbc.sql("""
                WITH slots AS (
                    SELECT block, row_no, bay_no, tier, row_number() OVER (ORDER BY tier, row_no, bay_no) AS n
                    FROM yard_slots WHERE block = 'D'
                )
                UPDATE yard_slots y SET container_number = 'FILU' || lpad(s.n::text, 6, '0') || '0'
                FROM slots s
                WHERE y.block = s.block AND y.row_no = s.row_no AND y.bay_no = s.bay_no AND y.tier = s.tier
                  AND s.n <= 210
                """).update();
        sendAndAwait(dropOff(ContainerNumber.of("SYN", 'U', 1), "TV-SYNC", at(0)));

        evaluator.evaluate(at(1));
        assertThat(active(YardUtilisationRule.ID, "D")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT severity FROM alerts WHERE rule = :r").param("r", YardUtilisationRule.ID)
                .query(String.class).single()).isEqualTo("WARNING");
    }
}
