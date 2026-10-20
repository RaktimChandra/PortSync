# Testing

The project is tested at four levels. Each level answers a different question, and the expensive
levels only cover what the cheap ones cannot.

| Level | Tools | Question it answers | Needs Docker |
|---|---|---|---|
| Unit | JUnit 5, AssertJ, Mockito | Is each rule and decision correct in isolation, at every boundary? | No |
| Property / simulation | JUnit 5 | Does the simulator only ever produce events the core accepts? | No |
| Integration | Testcontainers (real Kafka in KRaft mode + PostgreSQL 16), Awaitility | Does the whole service behave correctly against real infrastructure, under concurrency? | Yes |
| API | REST Assured (inside the integration suite) | Does every endpoint return the documented body and the standard error shape? | Yes |
| Frontend | Vitest, Testing Library | Do the check-digit helper, the live stream and key components behave? | No |
| End to end | `scripts/smoke-test.sh` in CI | Does the real Compose stack start, flow, reject, and expose metrics? Does normal operation dead-letter nothing, and do core and simulator agree on the yard? | Yes |

## How to run

```bash
./mvnw test                                # unit tests only (Surefire, *Test), no Docker needed
./mvnw verify                              # unit + integration tests (Failsafe, *IT), Docker must be running
./mvnw -pl portsync-core verify -Dit.test=DeadLetterIT    # one integration test class
./mvnw -pl portsync-events test -Dtest=ContainerNumberTest # one unit test class
cd frontend && npm test                    # Vitest
scripts/smoke-test.sh                      # full stack, Linux/macOS/WSL
```

On Windows, `mvnw.cmd` replaces `./mvnw`. Testcontainers finds Docker Desktop automatically.

## Unit tests

**ISO 6346** (`ContainerNumberTest`, `iso6346.test.ts`)
- CSQU3054383 validates; the check digit is 3.
- Every single-digit change in positions 5-11 fails.
- Every single-letter change fails *except* the four the standard cannot detect
  (MSQU..., WSQU..., CIQU..., CSGU...), because those letters' values differ by a multiple of 11.
  The test asserts that exact set, which documents a real property of ISO 6346 rather than
  pretending it does not exist.
- Remainder 10 maps to check digit 0; input is normalised; malformed input gets a reason.
- 10 000 generated numbers all validate; a corrupted check digit never does.

**Yard** (`YardModelTest`, `StackRulesTest`, `YardServiceTest`)
- Allocation picks the lowest stack, never floats, never collides, fills a block to exactly 240.
- Occupied and floating placements are refused; moving within a stack works.
- Removal lets the boxes above drop one tier.
- A seeded random walk of 5 000 places, moves and removals ends with zero invariant violations.
- `YardServiceTest`: a placement lands on top of its stack whatever tier was reported (never into an
  occupied slot, never floating), a full stack is refused, a box can be re-stacked within its own
  full stack, and on removal the database write order is "clear changed slots first, refill second"
  (the unique index is per statement).

**SLA rules** (`SlaRulesTest`): each of the five rules at its exact boundary (equal to the limit is
not a breach, one over is), skip conditions (crane not yet a full window at the berth, faulted
cranes for the productivity rule), severity escalation, and window scaling.

**Alert de-duplication** (`AlertReconcilerTest`): first breach opens; repeated breach refreshes,
never duplicates (also while ACKNOWLEDGED); two breaches for one entity in one pass open one alert;
cleared conditions auto-resolve; a RESOLVED alert does not block a new one.

**Processing decisions** (`EventProcessorTest`, Mockito): duplicates touch nothing; stale events are
recorded and ignored; equal timestamps apply; rejections are recorded, dead-lettered and do not
advance the entity version; a failing DLQ send and unexpected errors propagate (so the transaction
rolls back and Kafka retries). During the grace period a rejection another topic could fix is
deferred, not dead-lettered; on the final attempt it is; a rejection nothing can fix (bad check
digit) is dead-lettered at once.

**Grace period** (`TerminalEventListenerTest`, Mockito): a deferred event is retried until it applies,
with each retry counted in `portsync.events.deferred`; after the configured retries the last attempt
is final; an unparseable record goes straight to the DLQ without touching the processor.

**Impossible events** (`HandlerRulesTest`, Mockito): bad check digit, move on a faulted crane,
gate-out of a container not in the yard, unknown crane; and a repeated FAULT keeps the original
start time.

**Simulated clock** (`SimulatedClockTest`, `SimClockTest`): speed factor, extrapolation cap, watermark
never moves back, `now()` monotonic, pause and resume.

**Simulator** (`TerminalSimulationTest`): runs 48 simulated hours (and 24 h with three other seeds)
and replays every emitted event through an independent checker with the core's acceptance rules.
Any event the core would reject, or any per-entity timestamp going backwards, fails the test. Also:
same seed gives the same events; vessels complete their visits; injections that should be legal keep
the stream valid; injections that should be invalid really are (wrong check digit, true duplicates,
truly stale, unparseable).

**Event contract** (`TerminalEventContractTest`): one event of every type validates against the
published JSON Schema and survives a JSON round trip; bad envelopes are caught.

## Integration tests (Testcontainers)

`IntegrationTestBase` starts PostgreSQL 16 and Kafka 3.9.1 (KRaft) in Docker once per JVM and boots
the full Spring application against them. Tests talk to it only the way production does: produce
records to Kafka, then observe PostgreSQL, the DLQ topic and the HTTP API. All IT classes share one
Spring context on purpose: two contexts would be two consumers in one group splitting the
partitions.

| Test | Proves |
|---|---|
| `IdempotencyIT` | The same CRANE_MOVE delivered five times is applied once (one move row, discharge count 1, one history line, four DUPLICATE outcomes). A duplicated rejected event is dead-lettered once. |
| `OutOfOrderIT` | A RECOVERED stamped before the FAULT does not un-fault the crane; equal timestamps apply; a stale YARD_MOVE does not move the container and is not dead-lettered. |
| `DeadLetterIT` | Bad check digit, gate-out not in yard, move on faulted crane (with no partial state left), slot outside the layout, a fifth box on a full stack and unparseable JSON all land in the DLQ with the right reason; the consumer keeps going; the DLQ record carries the original value and reason headers. |
| `YardInvariantsIT` | Burst: 150 containers gated in and stacked by three consumer threads in parallel; two containers reported into one slot at once (one lands on the other); a "floating" report lands on the ground; a non-existent slot is refused; 20 pick-ups force rehandles; every placement is replayed as a duplicate. Afterwards the database yard equals an independent in-memory model slot for slot and the invariant audit is empty. |
| `CrossTopicConvergenceIT` | A removal on the gate topic and a placement on the yard topic for the same stack, applied in both possible orders, give the same final yard and no dead letters. A crane move consumed before the BERTH_ASSIGNED it depends on (different topic) waits for it and is applied, not dead-lettered. |
| `SlaAlertIT` | Fault alert opens after 15 min, is de-duplicated across evaluations, can be acknowledged (409 on a second ack), and auto-resolves on recovery. Productivity alert, manual resolve, and a new alert when the breach persists. Berth wait, truck turn time and yard utilisation alerts open and clear. |
| `ApiIT` | Every endpoint, including 400/404/405/409 paths and the common error body; actuator liveness/readiness with DB and Kafka; custom Prometheus metrics; OpenAPI document; SSE delivers `hello` and then a live `event` message. |

## Frontend tests (Vitest)

- `iso6346.test.ts`: the browser's check digit agrees with the backend's.
- `eventStream.test.ts`: reconnect with exponential back-off (1 s, 2 s, then capped), reset after a
  successful open, no reconnect after stop, JSON parsing. Uses a fake EventSource and fake timers.
- `YardGrid.test.tsx`: one button per stack with its height; clicking reports the stack; selection state.
- `AlertsTable.test.tsx`: empty state, plain-language rule names, acknowledge/resolve callbacks,
  buttons disabled for transitions the API would refuse.
- `KpiBand.test.tsx`: the five KPIs and the empty turn-time case.
- `ContainersPage.test.tsx`: a wrong check digit is explained while typing and never reaches the API;
  a valid number in the URL is looked up and its location and history shown; a valid but unknown
  number gets a clear "never seen" message (404). `fetch` is stubbed.
- `contrast.test.ts`: WCAG 2.1 AA (4.5:1) for the colour pairs the dashboard draws, read from
  `styles.css` for the dark theme and both light-theme blocks: every tone colour on panels, the
  warning tone on the page background, the primary button's text, a vessel label on its progress
  fill and a free berth's label; and that the two light blocks stay identical.
- `QuaySchematic.test.tsx`: the hull fill matches the share of moves done, and the vessel code
  over the fill is not drawn in the muted colour.

## What is deliberately not tested

- Browser end-to-end in this repository: the smoke test checks the dashboard is served and the API
  is reachable through its proxy, but not clicks in a real browser. Real-browser UI, BDD and
  axe-core accessibility scans of the running stack are run from outside, by the
  [TestHarness](https://github.com/RaktimChandra/TestHarness) PortSync adapter.
- Grafana panel queries beyond "the dashboard is provisioned".
- Behaviour across multiple core instances.

## Where the tests ran

The last full results, with dates, are in [RESULTS.md](RESULTS.md).
