# Measured results

Every number here was produced by a run whose date, machine and command are given next to it.
Nothing in this repository quotes a performance figure that is not in this file.

## Load test: 14 October 2026

**Machine.** GitHub-hosted runner `ubuntu-latest`: 4 vCPU AMD EPYC 7763, 15 GiB RAM, Linux
6.17.0-1022-azure, Docker 28.0.4. **Everything ran on that one VM at the same time**: Kafka,
PostgreSQL, portsync-core, the simulator (paused during measurement), nginx, Prometheus, Grafana and
the load generator, all in Docker Compose with default settings. A developer laptop or a dedicated
database will give different numbers.

**Command.** `scripts/run-loadtest.sh <events> <rate>`, which runs
`docker compose --profile loadtest run --rm loadtest` against the running stack, three burst runs
then two paced runs, back to back.

**Workload.** Per container one GATE_IN (drop-off) and its GATE_OUT on the same key; 2% of
containers carry a wrong check digit and both their events are rejected to the DLQ. Every event is
fully processed: idempotency claim, out-of-order check, row locks, writes to `containers`,
`truck_visits`, `container_history`, `entity_versions`, `processed_events`, and a synchronous DLQ
send for rejections. The core consumes with 3 threads (3 partitions).

**Definitions.**
- *Throughput* = events / (time the last event was committed in PostgreSQL - time the first was sent).
- *End-to-end latency* = commit time in portsync-core - Kafka record timestamp, per event, from an
  HdrHistogram that is reset before each run.
- *Processing time* = validate + apply + commit for one event, inside the core.

### Burst: maximum throughput (20 000 events, sent as fast as possible)

| Run (UTC) | Produced into Kafka | Throughput end to end | Processing p50 / p95 / p99 | Outcomes |
|---|---|---|---|---|
| 06:05:04 | 20 000 in 1 877 ms | **1 077 events/s** | 1.85 / 5.47 / 11.87 ms | 19 600 applied, 400 rejected |
| 06:05:23 | 20 000 in 1 347 ms | **1 437 events/s** | 1.72 / 3.70 / 5.38 ms | 19 614 applied, 386 rejected |
| 06:05:43 | 20 000 in 1 246 ms | **1 442 events/s** | 1.73 / 3.60 / 5.39 ms | 19 604 applied, 396 rejected |

The first run is slower because the core's JVM is still warming up (JIT compilation). In burst
mode the producer is about ten times faster than the consumer, so most events wait in Kafka:
end-to-end latency here (p50 6.0-9.7 s, p99 12.4-16.4 s) measures that queue, not per-event delay.

### Paced: latency below capacity (10 000 events at 400 events/s)

| Run (UTC) | Throughput | End-to-end latency p50 / p95 / p99 / max | Processing p50 / p95 / p99 |
|---|---|---|---|
| 06:06:13 | 400 events/s | 7.0 / 15.0 / 32.0 / 77.1 ms | 1.79 / 3.82 / 5.52 ms |
| 06:06:44 | 400 events/s | **7.0 / 8.0 / 11.0 / 52.0 ms** | 1.69 / 2.59 / 3.95 ms |

At about 28% of the measured burst capacity, an event is committed about 7 ms after it was
produced, with p99 around 11-32 ms.

### Reading these numbers

- The ceiling is set by per-event transactions: each event is several SQL statements and one commit,
  on three threads, against PostgreSQL on the same 4-vCPU VM as everything else. That is the cost of
  exactly-once effects with row locks; it was a deliberate choice (see DESIGN.md).
- The simulator at 60x produces on the order of a few events per second, so the measured headroom is
  several hundred times the demo load.
- Obvious ways to go faster, not done: commit offsets and database writes in batches, more partitions
  and consumer threads, PostgreSQL on its own host. Each would need re-measuring.

### Reproduce

```bash
docker compose up -d --build
scripts/run-loadtest.sh 20000 0     # burst
scripts/run-loadtest.sh 10000 400   # paced
```

On Windows PowerShell:

```powershell
$env:LOADTEST_EVENTS=20000; $env:LOADTEST_RATE=0; docker compose --profile loadtest run --rm loadtest
```

Each run writes a Markdown and a JSON report into `loadtest-results/`. When you run it on your own
machine, add a section above with the date, your CPU, RAM and OS, and the figures you got.

## Test runs: 14 October 2026

Run on GitHub-hosted `ubuntu-latest` (4 vCPU, Docker 28.0.4, Temurin 21.0.12) with `./mvnw -B verify`
and `npm test`:

| Suite | Tests | Result |
|---|---|---|
| portsync-events unit tests | 65 | passed |
| simulator unit and wiring tests | 11 | passed |
| portsync-core unit tests (JUnit 5, Mockito) | 43 | passed |
| portsync-core integration tests (Testcontainers: Kafka 3.9.1 KRaft, PostgreSQL 16; REST Assured) | 29 | passed |
| frontend (Vitest) | 17 | passed |
| End-to-end smoke test of the Compose stack (`scripts/smoke-test.sh`) | 17 checks | passed |

The CI badge in the README shows the status of the latest commit.
