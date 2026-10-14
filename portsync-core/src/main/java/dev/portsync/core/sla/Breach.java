package dev.portsync.core.sla;

import java.time.Duration;

/**
 * One rule currently being broken by one entity, e.g. "QC03 faulted for 22 min (max 15)".
 *
 * @param rule      rule id, e.g. CRANE_FAULT_DURATION
 * @param entityId  the crane, vessel, truck visit or block concerned
 * @param observed  the measured value (minutes, moves/hour, ratio)
 * @param threshold the configured limit it was compared with
 */
public record Breach(String rule, String entityId, Severity severity, String message, double observed, double threshold) {

    /**
     * A duration for an alert message, to the second: "45 min 40 s". Rules fire only when strictly
     * above the limit, so whole minutes alone could print "45 min (maximum 45)" for 45 min 40 s.
     */
    public static String describe(Duration d) {
        long minutes = d.toMinutes();
        int seconds = d.toSecondsPart();
        return seconds == 0 ? minutes + " min" : minutes + " min " + seconds + " s";
    }

    /** A duration as fractional minutes, for {@link #observed()}. */
    public static double minutes(Duration d) {
        return d.toMillis() / 60_000.0;
    }
}
