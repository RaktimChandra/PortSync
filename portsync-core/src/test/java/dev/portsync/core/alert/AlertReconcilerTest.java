package dev.portsync.core.alert;

import dev.portsync.core.sla.Breach;
import dev.portsync.core.sla.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlertReconcilerTest {

    private static final Instant T = Instant.parse("2026-10-14T08:00:00Z");

    private static Breach breach(String entity) {
        return new Breach("CRANE_FAULT_DURATION", entity, Severity.CRITICAL, entity + " down", 20, 15);
    }

    private static Alert alert(long id, String entity, AlertState state) {
        return new Alert(id, "CRANE_FAULT_DURATION", entity, "CRITICAL", state, entity + " down", 16.0, 15.0, T, T,
                null, null, null);
    }

    @Test
    @DisplayName("first breach opens an alert")
    void newBreachOpens() {
        AlertReconciler.Plan plan = AlertReconciler.plan(List.of(), List.of(breach("QC01")));
        assertThat(plan.toOpen()).extracting(Breach::entityId).containsExactly("QC01");
        assertThat(plan.toRefresh()).isEmpty();
        assertThat(plan.toResolve()).isEmpty();
    }

    @Test
    @DisplayName("same rule + entity while an alert is OPEN is a refresh, not a second alert")
    void repeatedBreachIsDeduplicated() {
        AlertReconciler.Plan plan = AlertReconciler.plan(List.of(alert(1, "QC01", AlertState.OPEN)),
                List.of(breach("QC01")));
        assertThat(plan.toOpen()).isEmpty();
        assertThat(plan.toRefresh()).singleElement().satisfies(r -> assertThat(r.alert().id()).isEqualTo(1));
    }

    @Test
    @DisplayName("an ACKNOWLEDGED alert also suppresses a duplicate")
    void acknowledgedAlertAlsoDeduplicates() {
        AlertReconciler.Plan plan = AlertReconciler.plan(List.of(alert(1, "QC01", AlertState.ACKNOWLEDGED)),
                List.of(breach("QC01")));
        assertThat(plan.toOpen()).isEmpty();
        assertThat(plan.toRefresh()).hasSize(1);
    }

    @Test
    @DisplayName("two breaches for the same entity in one evaluation open only one alert")
    void duplicateBreachesInOneEvaluationCollapse() {
        AlertReconciler.Plan plan = AlertReconciler.plan(List.of(), List.of(breach("QC01"), breach("QC01")));
        assertThat(plan.toOpen()).hasSize(1);
    }

    @Test
    @DisplayName("different entities get separate alerts")
    void differentEntitiesAreSeparate() {
        AlertReconciler.Plan plan = AlertReconciler.plan(List.of(alert(1, "QC01", AlertState.OPEN)),
                List.of(breach("QC01"), breach("QC02")));
        assertThat(plan.toOpen()).extracting(Breach::entityId).containsExactly("QC02");
        assertThat(plan.toRefresh()).hasSize(1);
    }

    @Test
    @DisplayName("active alert whose condition cleared is auto-resolved")
    void clearedConditionResolves() {
        AlertReconciler.Plan plan = AlertReconciler.plan(
                List.of(alert(1, "QC01", AlertState.OPEN), alert(2, "QC02", AlertState.ACKNOWLEDGED)),
                List.of(breach("QC02")));
        assertThat(plan.toResolve()).extracting(Alert::id).containsExactly(1L);
        assertThat(plan.toRefresh()).extracting(r -> r.alert().id()).containsExactly(2L);
    }

    @Test
    @DisplayName("a manually RESOLVED alert does not block a new alert if the breach persists")
    void resolvedAlertsAreIgnored() {
        AlertReconciler.Plan plan = AlertReconciler.plan(List.of(alert(1, "QC01", AlertState.RESOLVED)),
                List.of(breach("QC01")));
        assertThat(plan.toOpen()).hasSize(1);
        assertThat(plan.toResolve()).isEmpty();
    }

    @Test
    void nothingToDo() {
        assertThat(AlertReconciler.plan(List.of(), List.of()).isEmpty()).isTrue();
    }
}
