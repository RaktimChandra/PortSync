# Measured results

Every number here was produced by a run whose date, machine and command are given next to it.
Nothing in this repository quotes a performance figure that is not in this file.

## Load test: 14 October 2026, 08:22-08:24 UTC

**Code.** The version with the cross-topic grace period (one listener container per topic, three
threads each, connection pool 20).

**Machine.** GitHub-hosted runner `ubuntu-latest`: 4 vCPU AMD EPYC 9V74, 15 GiB RAM, Linux
6.17.0-1022-azure, Docker 28.0.4. **Everything ran on that one shared VM at the same time**: Kafka,
PostgreSQL, portsync-core, the simulator (paused before measuring), nginx, Prometheus, Grafana and the
load generator, in Docker Compose with default settings. A laptop or a dedicated database server
will give different numbers.

**Command.** A fresh `docker compose up -d --build`, wait for readiness and the simulator's opening
inventory, pause the simulator, then `scripts/run-loadtest.sh 20000 0` three times (burst) and
`scripts/run-loadtest.sh 10000 400` twice (paced), back to back.

**Workload.** Per container one GATE_IN (drop-off) and its GATE_OUT on the same key; 2% of
containers carry a wrong check digit and both their events are rejected to the DLQ. Every event is
fully processed: idempotency claim, out-of-order check, row locks, writes to `containers`,
`truck_visits`, `container_history`, `entity_versions`, `processed_events`, and a synchronous DLQ
send for each rejection.

**Definitions.**
- *Throughput* = events / (time the last event was committed in PostgreSQL - time the first was sent).
- *End-to-end latency* = commit time in portsync-core - Kafka record timestamp, per event, from an
  HdrHistogram reset before each run.
- *Processing time* = validate + apply + commit for one event, inside the core.

### Burst: maximum throughput (20 000 events, sent as fast as possible)

| Run (UTC) | Produced into Kafka | Throughput end to end | Processing p50 / p95 / p99 | Outcomes |
|---|---|---|---|---|
| 08:22:00 | 20 000 in 779 ms | **642 events/s** | 1.37 / 7.81 / 113.22 ms | 19 596 applied, 404 rejected |
| 08:22:44 | 20 000 in 722 ms | **618 events/s** | 1.38 / 10.74 / 92.29 ms | 19 580 applied, 420 rejected |
| 08:23:05 | 20 000 in 816 ms | **1 236 events/s** | 1.30 / 3.34 / 26.83 ms | 19 574 applied, 426 rejected |

Every event got exactly the expected outcome (applied, or rejected for its bad check digit); no
duplicates, no stale events, no malformed records. In burst mode the producer is far faster than the
consumer, so most events wait in Kafka: end-to-end latency (p50 7.6-20.7 s) measures that queue,
not per-event delay.

**The spread is real and I have not explained it fully.** Median processing time is the same in all
three runs (about 1.3-1.4 ms); what differs is the tail (p99 113 ms vs 27 ms). The first two runs
started within a minute of the stack coming up, while the JVM was still compiling hot code and the
yard, history and ledger tables were growing from empty, all on a VM shared by nine containers. The
third run is the best estimate of the steady state; a fair claim is "roughly 600-1 200 events/s on
this machine", not a single figure.

### Paced: latency below capacity (10 000 events at 400 events/s)

| Run (UTC) | Throughput | End-to-end latency p50 / p95 / p99 / max | Processing p50 / p95 / p99 |
|---|---|---|---|
| 08:23:35 | 400 events/s | **7.0 / 303 / 442 / 512 ms** | 1.35 / 7.92 / 67.65 ms |
| 08:24:06 | 386 events/s | 120 / 872 / 1 053 / 1 211 ms | 1.35 / 22.59 / 120.26 ms |

At 400 events/s the median event is committed 7 ms after it was produced in the first paced run.
The second run started straight after it on the same, by then larger, tables and fell slightly behind
(386 events/s, p50 120 ms), which shows 400 events/s is close to this VM's sustained limit for this
workload once the burst-mode tail appears.

### Reading these numbers

- The ceiling is set by per-event transactions: each event is several SQL statements and one commit,
  against PostgreSQL on the same 4-vCPU VM as everything else. That is the cost of exactly-once
  effects with row locks; it was a deliberate choice (DESIGN.md, section 3).
- The simulator at 60x produces a few events per second, so even the slowest run has more than a
  hundred times the demo load in headroom.
- Obvious ways to go faster, not done: commit offsets and database writes in batches, more partitions
  and consumer threads, PostgreSQL on its own host. Each would need re-measuring here.

## Test suites: 14 October 2026

Run on GitHub-hosted `ubuntu-latest` runners (4 vCPU, 15 GiB, Docker 28.0.4) by the verification
workflow and by CI on `main`:

| Suite | Result |
|---|---|
| `portsync-events` unit tests (Surefire) | 65 passed |
| `simulator` unit tests (Surefire) | 11 passed |
| `portsync-core` unit tests (Surefire) | 51 passed in the verification run; 53 (with two SLA boundary tests added after it) passed in CI on `main` at 9aa77e3 |
| `portsync-core` integration tests (Failsafe, Testcontainers: Kafka 3.9.1 KRaft + PostgreSQL 16, REST Assured) | 33 passed |
| Frontend (Vitest) | 17 passed in the verification run; 20 (with the container lookup tests) passed locally and in CI on `main` at 9aa77e3 |
| End-to-end smoke test of the Compose stack (`scripts/smoke-test.sh`) | all checks passed, DLQ empty in normal operation, core and simulator yards identical block by block |

The current totals for every commit are in the Actions tab of the repository; CI runs all of the
above on each push to `main`.

## Simulator pause race, 21 October 2026

TestHarness pauses the simulator and compares its yard with the core's, block by block. Twice
(TestHarness runs 39464783586 and 39478295663) the core held one container more than the
status read after the pause, the second time for the whole minute the test waited. The tick
checked "paused?" without a lock and then ran, so a tick that had passed the check just before
a pause still changed the yard and published its events afterwards. The tick and the pause now
share the simulation's lock. `SimulationRunnerTest` holds a 300 ms tick and checks that
`pause()` returns only after it, and that no tick runs while paused. CI on the fix (run
39485289857): **164 backend unit tests, 0 failed** (core 86, events 65, simulator 13).

## Accessibility: colour contrast, 20 October 2026

TestHarness's PortSync adapter scans the running dashboard with axe-core (WCAG 2.0/2.1 A + AA).
It found two contrast failures in the light theme, each visible only in some terminal states:

| Where | Colours | Contrast before | Fix | After |
|---|---|---:|---|---:|
| warning tone (`.tone-warn`, KPI warnings) once warning alerts are open | `--signal` `#b06c00` on white | 4.21:1 (3.74:1 on the page background) | light `--signal` `#965b00` | 5.54:1 (4.92:1) |
| a berthed vessel's code in the quay schematic once it is almost fully worked | `--muted` `#536a7c` on the progress fill `#9dc6c2` | 3.03:1 (dark theme: 2.97:1) | the label takes `--ink` over the fill | 8.90:1 (dark: 6.05:1) |

Auditing every token pair with `contrast.test.ts` found a third that no scan had reached: the
primary button's dark text on the light-theme `--signal` was 4.41:1. It now uses
`--on-signal` (white in the light theme: 5.54:1; unchanged `#1a1204` in the dark theme:
9.14:1). The test fails on the previous palette (7 failing assertions) and passes on this one.

