package dev.portsync.core.sla;

/**
 * One rule currently being broken by one entity, e.g. "QC03 faulted for 22 min (max 15)".
 *
 * @param rule      rule id, e.g. CRANE_FAULT_DURATION
 * @param entityId  the crane, vessel, truck visit or block concerned
 * @param observed  the measured value (minutes, moves/hour, ratio)
 * @param threshold the configured limit it was compared with
 */
public record Breach(String rule, String entityId, Severity severity, String message, double observed, double threshold) {
}
