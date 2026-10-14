package dev.portsync.core.ops;

import dev.portsync.core.ingest.Outcome;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Resettable in-process statistics used by the load test and the overview page. Micrometer timers
 * feed Prometheus continuously; this class answers "what happened since I pressed reset", which is
 * what a repeatable load test needs. Latencies are kept in HdrHistogram recorders (lock-free,
 * microsecond resolution, 3 significant digits).
 */
@Component
public class ProcessingStats {

    private final Map<Outcome, LongAdder> counts = new EnumMap<>(Outcome.class);
    private final LongAdder malformed = new LongAdder();
    private volatile Recorder latency = newRecorder();
    private volatile Recorder processing = newRecorder();
    private Histogram latencyTotal = new Histogram(3_600_000_000L, 3);
    private Histogram processingTotal = new Histogram(3_600_000_000L, 3);
    private volatile long firstAtMillis;
    private volatile long lastAtMillis;
    private volatile long resetAtMillis = System.currentTimeMillis();

    public ProcessingStats() {
        for (Outcome o : Outcome.values()) {
            counts.put(o, new LongAdder());
        }
    }

    private static Recorder newRecorder() {
        return new Recorder(3_600_000_000L, 3);
    }

    public void record(Outcome outcome, long latencyMicros, long processingMicros) {
        counts.get(outcome).increment();
        latency.recordValue(Math.min(Math.max(latencyMicros, 0), 3_600_000_000L));
        processing.recordValue(Math.min(Math.max(processingMicros, 0), 3_600_000_000L));
        long now = System.currentTimeMillis();
        if (firstAtMillis == 0) {
            firstAtMillis = now;
        }
        lastAtMillis = now;
    }

    public void recordMalformed() {
        malformed.increment();
    }

    public long total() {
        long sum = malformed.sum();
        for (LongAdder a : counts.values()) {
            sum += a.sum();
        }
        return sum;
    }

    public long count(Outcome outcome) {
        return counts.get(outcome).sum();
    }

    public synchronized void reset() {
        counts.values().forEach(LongAdder::reset);
        malformed.reset();
        latency = newRecorder();
        processing = newRecorder();
        latencyTotal = new Histogram(3_600_000_000L, 3);
        processingTotal = new Histogram(3_600_000_000L, 3);
        firstAtMillis = 0;
        lastAtMillis = 0;
        resetAtMillis = System.currentTimeMillis();
    }

    public synchronized Snapshot snapshot() {
        latencyTotal.add(latency.getIntervalHistogram());
        processingTotal.add(processing.getIntervalHistogram());
        Map<String, Long> byOutcome = new java.util.LinkedHashMap<>();
        counts.forEach((k, v) -> byOutcome.put(k.name(), v.sum()));
        byOutcome.put("MALFORMED", malformed.sum());
        long windowMillis = firstAtMillis == 0 ? 0 : Math.max(1, lastAtMillis - firstAtMillis);
        long recorded = latencyTotal.getTotalCount();
        return new Snapshot(
                total(),
                byOutcome,
                resetAtMillis,
                firstAtMillis,
                lastAtMillis,
                windowMillis == 0 ? 0 : recorded * 1000.0 / windowMillis,
                Percentiles.of(latencyTotal),
                Percentiles.of(processingTotal));
    }

    /** Snapshot since the last reset. Times are wall-clock epoch millis. */
    public record Snapshot(long total, Map<String, Long> byOutcome, long resetAtMillis, long firstEventAtMillis,
                           long lastEventAtMillis, double eventsPerSecondWhileBusy,
                           Percentiles endToEndLatencyMs, Percentiles processingTimeMs) {
    }

    /** Distribution summary in milliseconds. */
    public record Percentiles(long count, double mean, double p50, double p95, double p99, double max) {

        static Percentiles of(Histogram h) {
            if (h.getTotalCount() == 0) {
                return new Percentiles(0, 0, 0, 0, 0, 0);
            }
            return new Percentiles(h.getTotalCount(),
                    round(h.getMean() / 1000.0),
                    round(h.getValueAtPercentile(50) / 1000.0),
                    round(h.getValueAtPercentile(95) / 1000.0),
                    round(h.getValueAtPercentile(99) / 1000.0),
                    round(h.getMaxValue() / 1000.0));
        }

        private static double round(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }
}
