package dev.portsync.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.portsync.events.ContainerNumber;
import dev.portsync.events.EventJson;
import dev.portsync.events.EventType;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.payload.GateDirection;
import dev.portsync.events.payload.GateIn;
import dev.portsync.events.payload.GateOut;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;

/**
 * Load test: pushes a fixed number of events into Kafka as fast as the producer allows and asks the
 * core service how long it took to apply them.
 *
 * <p>Workload (all of it real processing, not no-ops): for each container a DROP_OFF gate-in
 * followed by the matching gate-out on the same key, so every pair is ordered and valid; a
 * configurable share of gate-ins carry a wrong check digit and go down the reject/DLQ path. Each
 * event is claimed in processed_events, checked against entity_versions, and writes containers,
 * truck_visits and container_history rows in one transaction.
 *
 * <p>Measured:
 * <ul>
 *   <li><b>throughput</b> = events / (time the last event was committed - time the first was sent)</li>
 *   <li><b>end-to-end latency</b> per event = commit time - Kafka record timestamp (from the core's
 *       resettable HdrHistogram, so it covers exactly this run)</li>
 * </ul>
 *
 * <pre>
 * java -jar loadtest.jar --events=20000 --bootstrap=localhost:9094 --core=http://localhost:8080 \
 *      --simulator=http://localhost:8081 --invalid-share=0.02 --out=loadtest-results
 * </pre>
 */
public final class LoadTest {

    private final Map<String, String> args;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = EventJson.mapper();

    private LoadTest(Map<String, String> args) {
        this.args = args;
    }

    public static void main(String[] argv) throws Exception {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        Map<String, String> args = new HashMap<>();
        for (String a : argv) {
            if (a.startsWith("--") && a.contains("=")) {
                args.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
            }
        }
        new LoadTest(args).run();
    }

    private String arg(String name, String fallback) {
        String env = System.getenv("LOADTEST_" + name.toUpperCase(Locale.ROOT).replace('-', '_'));
        return args.getOrDefault(name, env != null ? env : fallback);
    }

    private void run() throws Exception {
        int events = Integer.parseInt(arg("events", "20000"));
        if (events % 2 != 0) {
            events++;
        }
        double invalidShare = Double.parseDouble(arg("invalid-share", "0.02"));
        // 0 = as fast as possible (burst: measures maximum throughput, latency is mostly queueing).
        // >0 = paced at this many events/s (measures latency when the core is not saturated).
        int rate = Integer.parseInt(arg("rate", "0"));
        String bootstrap = arg("bootstrap", "localhost:9094");
        String core = arg("core", "http://localhost:8080");
        String simulator = arg("simulator", "http://localhost:8081");
        Path out = Path.of(arg("out", "loadtest-results"));

        System.out.printf("PortSync load test: %,d events (%s) -> %s, measuring via %s%n", events,
                rate > 0 ? "paced at " + rate + " events/s" : "burst", bootstrap, core);
        boolean pausedSimulator = post(simulator + "/api/simulation/pause");
        System.out.println(pausedSimulator ? "Simulator paused so only load-test events are measured."
                : "Simulator not reachable; make sure it is stopped or paused, or its events will be counted too.");
        Thread.sleep(2_000);
        if (!post(core + "/api/ops/stats/reset")) {
            throw new IllegalStateException("Cannot reach " + core + "/api/ops/stats/reset - is portsync-core running?");
        }

        List<TerminalEvent> workload = buildWorkload(events, invalidShare);
        long expectedRejected = workload.stream().filter(e -> !ContainerNumber.isValid(e.entityId())).count();

        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, "65536");

        long sendStart;
        long sendEnd;
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            // Warm the connection and metadata so the first measured send is not a cold start.
            producer.partitionsFor("terminal.gate");
            sendStart = System.currentTimeMillis();
            List<Future<?>> acks = new ArrayList<>(workload.size());
            long startNanos = System.nanoTime();
            for (int i = 0; i < workload.size(); i++) {
                if (rate > 0) {
                    long due = startNanos + (long) (i * 1_000_000_000.0 / rate);
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        java.util.concurrent.locks.LockSupport.parkNanos(wait);
                    }
                }
                TerminalEvent e = workload.get(i);
                acks.add(producer.send(new ProducerRecord<>(e.topic(), e.partitionKey(), EventJson.write(e))));
            }
            producer.flush();
            for (Future<?> f : acks) {
                f.get();
            }
            sendEnd = System.currentTimeMillis();
        }
        System.out.printf("Produced %,d events in %,d ms (%,.0f events/s into Kafka)%n", workload.size(),
                sendEnd - sendStart, workload.size() * 1000.0 / Math.max(1, sendEnd - sendStart));

        JsonNode stats = awaitProcessed(core, workload.size(), Duration.ofMinutes(15));
        long lastCommit = stats.path("lastEventAtMillis").asLong();
        long wall = Math.max(1, lastCommit - sendStart);
        double throughput = workload.size() * 1000.0 / wall;
        JsonNode latency = stats.path("endToEndLatencyMs");
        JsonNode processing = stats.path("processingTimeMs");
        JsonNode byOutcome = stats.path("byOutcome");

        String report = report(events, rate, invalidShare, expectedRejected, sendEnd - sendStart, wall, throughput,
                latency, processing, byOutcome);
        System.out.println();
        System.out.println(report);
        Files.createDirectories(out);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Files.writeString(out.resolve("loadtest-" + stamp + ".md"), report);
        Files.writeString(out.resolve("loadtest-" + stamp + ".json"), json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("events", events, "invalidShare", invalidShare, "produceMillis",
                        sendEnd - sendStart, "endToEndMillis", wall, "throughputEventsPerSecond", throughput,
                        "coreStats", stats, "machine", machine())));
        System.out.println("Saved to " + out.toAbsolutePath());

        if (pausedSimulator) {
            post(simulator + "/api/simulation/resume");
        }
    }

    /** Pairs of gate-in / gate-out per container; some gate-ins carry a corrupted check digit. */
    private List<TerminalEvent> buildWorkload(int events, double invalidShare) {
        // A fresh owner code per run, so numbers never collide with earlier runs' containers.
        long seed = System.nanoTime();
        String owner = "L" + (char) ('A' + Math.floorMod(seed, 26)) + (char) ('A' + Math.floorMod(seed / 26, 26));
        java.util.SplittableRandom random = new java.util.SplittableRandom(seed);
        int pairs = events / 2;
        Instant t0 = Instant.now().minus(Duration.ofDays(30));
        List<TerminalEvent> list = new ArrayList<>(events);
        int serialBase = random.nextInt(500_000);
        for (int i = 0; i < pairs; i++) {
            String number = ContainerNumber.of(owner, 'U', serialBase + i);
            if (random.nextDouble() < invalidShare) {
                number = ContainerNumber.withWrongCheckDigit(number);
            }
            String visit = "LT-" + owner + "-" + i;
            Instant in = t0.plusSeconds(i);
            list.add(TerminalEvent.of(EventType.GATE_IN, in, number, "loadtest",
                    new GateIn(visit, "G" + (1 + i % 4), GateDirection.DROP_OFF, "LT-" + i)));
            list.add(TerminalEvent.of(EventType.GATE_OUT, in.plusSeconds(600), number, "loadtest",
                    new GateOut(visit, "G" + (1 + i % 4))));
        }
        return list;
    }

    private JsonNode awaitProcessed(String core, int expected, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        JsonNode stats = null;
        long lastPrint = 0;
        while (System.currentTimeMillis() < deadline) {
            stats = get(core + "/api/ops/stats");
            long done = stats.path("total").asLong();
            if (System.currentTimeMillis() - lastPrint > 2_000) {
                System.out.printf("  processed %,d / %,d%n", done, expected);
                lastPrint = System.currentTimeMillis();
            }
            if (done >= expected) {
                return stats;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Timed out: core processed " + (stats == null ? 0 : stats.path("total").asLong())
                + " of " + expected + " events");
    }

    private String report(int events, int rate, double invalidShare, long expectedRejected, long produceMillis, long wall,
                          double throughput, JsonNode latency, JsonNode processing, JsonNode byOutcome) {
        Map<String, String> m = machine();
        StringBuilder sb = new StringBuilder();
        sb.append("## Load test run ").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))).append("\n\n");
        sb.append("| Item | Value |\n|---|---|\n");
        sb.append(row("Mode", rate > 0 ? "Paced at " + rate + " events/s" : "Burst (as fast as the producer can send)"));
        sb.append(row("Events", String.format("%,d (%s invalid share, %d expected rejections)", events,
                String.format("%.0f%%", invalidShare * 100), expectedRejected)));
        sb.append(row("Outcomes", byOutcome.toString()));
        sb.append(row("Produce time (client -> Kafka acks)", produceMillis + " ms"));
        sb.append(row("First send -> last commit", wall + " ms"));
        sb.append(row("**Throughput (end to end)**", String.format("**%,.0f events/s**", throughput)));
        sb.append(row("End-to-end latency p50 / p95 / p99 / max", String.format("%s / %s / %s / %s ms",
                latency.path("p50").asText(), latency.path("p95").asText(), latency.path("p99").asText(),
                latency.path("max").asText())));
        sb.append(row("Processing time per event p50 / p95 / p99", String.format("%s / %s / %s ms",
                processing.path("p50").asText(), processing.path("p95").asText(), processing.path("p99").asText())));
        sb.append(row("Load generator machine", m.get("os") + ", " + m.get("cpus") + " CPUs, Java " + m.get("java")));
        sb.append("\nEnd-to-end latency = commit time in portsync-core - Kafka record timestamp. ");
        sb.append(rate > 0
                ? "Paced below capacity, so it reflects per-event delay without a backlog.\n"
                : "In burst mode events are produced faster than they are consumed, so later events wait in Kafka "
                + "and latency mostly measures that queue, not per-event delay.\n");
        return sb.toString();
    }

    private static String row(String k, String v) {
        return "| " + k + " | " + v + " |\n";
    }

    private static Map<String, String> machine() {
        Map<String, String> m = new HashMap<>();
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        m.put("cpus", Integer.toString(Runtime.getRuntime().availableProcessors()));
        m.put("java", System.getProperty("java.version"));
        m.put("maxHeapMb", Long.toString(Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        m.put("jvm", ManagementFactory.getRuntimeMXBean().getVmName());
        return m;
    }

    private boolean post(String url) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() / 100 == 2;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private JsonNode get(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("GET " + url + " -> " + r.statusCode());
        }
        return json.readTree(r.body());
    }
}
