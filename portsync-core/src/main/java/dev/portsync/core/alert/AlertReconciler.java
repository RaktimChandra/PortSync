package dev.portsync.core.alert;

import dev.portsync.core.sla.Breach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides how alerts change after one evaluation of a rule. Pure: no database, no clock.
 *
 * <ul>
 *   <li>Breach with no active alert for (rule, entity) -> <b>open</b> a new alert.</li>
 *   <li>Breach with an active alert -> <b>refresh</b> it (new value/message, last seen). Never a
 *       second alert: this is the de-duplication.</li>
 *   <li>Active alert whose (rule, entity) is no longer in breach -> <b>auto-resolve</b> it.</li>
 *   <li>Several breaches with the same key in one evaluation -> the last one wins.</li>
 * </ul>
 */
public final class AlertReconciler {

    private AlertReconciler() {
    }

    public record Plan(List<Breach> toOpen, List<Refresh> toRefresh, List<Alert> toResolve) {

        public boolean isEmpty() {
            return toOpen.isEmpty() && toRefresh.isEmpty() && toResolve.isEmpty();
        }
    }

    public record Refresh(Alert alert, Breach breach) {
    }

    /**
     * @param active   currently OPEN or ACKNOWLEDGED alerts for the rule being reconciled
     * @param breaches current breaches of that same rule
     */
    public static Plan plan(List<Alert> active, List<Breach> breaches) {
        Map<String, Breach> current = new LinkedHashMap<>();
        for (Breach b : breaches) {
            current.put(key(b.rule(), b.entityId()), b);
        }
        Map<String, Alert> activeByKey = new LinkedHashMap<>();
        for (Alert a : active) {
            if (a.state().isActive()) {
                activeByKey.putIfAbsent(a.key(), a);
            }
        }

        List<Breach> toOpen = new ArrayList<>();
        List<Refresh> toRefresh = new ArrayList<>();
        for (Map.Entry<String, Breach> e : current.entrySet()) {
            Alert existing = activeByKey.get(e.getKey());
            if (existing == null) {
                toOpen.add(e.getValue());
            } else {
                toRefresh.add(new Refresh(existing, e.getValue()));
            }
        }
        List<Alert> toResolve = new ArrayList<>();
        for (Map.Entry<String, Alert> e : activeByKey.entrySet()) {
            if (!current.containsKey(e.getKey())) {
                toResolve.add(e.getValue());
            }
        }
        return new Plan(toOpen, toRefresh, toResolve);
    }

    static String key(String rule, String entityId) {
        return rule + "|" + entityId;
    }
}
