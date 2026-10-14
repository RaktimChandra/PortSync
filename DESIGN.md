# Design decisions

Each section states the decision, why it was made, and what it costs. Where a simpler or more
"enterprise" alternative exists, it is named and the reason it was not used is given.

## 1. Why Kafka (and not REST calls or a plain queue)

**Decision.** The simulator publishes events to Kafka topics; the core consumes them.

**Why.**
- *Decoupling in time.* If the core is down or slow, the simulator keeps producing and nothing is
  lost: events wait in the log and the core catches up. Consumer lag (a metric) shows how far behind it is.
- *Ordering where it matters.* Kafka guarantees order within a partition. Keying by entity
  (section 2) gives per-container, per-crane and per-vessel order, which is exactly what the
  business rules need, while different entities are still processed in parallel.
- *Replay.* The log is the source of truth for the DLQ page (the core materialises `terminal.dlq`
  into a table), and a new consumer could rebuild state from the beginning.
- It is also what real terminal integrations look like: equipment, gate and berth systems emitting
  events that a central system has to reconcile.

**Cost.** At-least-once delivery means duplicates are normal, so the consumer must be idempotent
(section 3). Ordering is only per partition, so cross-topic order is not guaranteed (section 4).
A broker is one more thing to run, monitor and size.

**KRaft, not ZooKeeper.** Kafka 3.9 runs its own controller quorum. One process is both broker and
controller in Compose, which halves the moving parts. ZooKeeper mode is deprecated.

## 2. Topics, partitions and keys

| Topic | Events | Record key |
|---|---|---|
| `terminal.berth` | VESSEL_ARRIVED, BERTH_ASSIGNED, VESSEL_DEPARTED | vessel visit id |
| `terminal.crane` | CRANE_STATUS | crane id |
| `terminal.crane` | CRANE_MOVE | container number |
| `terminal.gate` | GATE_IN, GATE_OUT | container number |
| `terminal.yard` | YARD_MOVE | container number |
| `terminal.dlq` | anything rejected | original key |

Three partitions per topic, so the core runs three consumer threads in parallel.

**The key is always the event's `entityId`.** `EventType` binds each type to its topic and entity
kind in one place (`portsync-events`), so the simulator, the load test and the tests cannot route
an event wrongly.

**Berth events are keyed by vessel, not by berth.** A vessel visit's lifecycle (arrive → berth →
depart) must stay in order; keying the arrival by vessel and the assignment by berth would put them
on different partitions and BERTH_ASSIGNED could overtake VESSEL_ARRIVED. The berth id travels in
the payload.

**CRANE_MOVE is keyed by container, not crane.** One container can be moved by different cranes
(discharged by QC02, later loaded by QC05). Keyed by crane, those moves could land on different
partitions and the load could be processed before the discharge. Keyed by container, all of one
box's crane moves share a partition and stay in order. The crane's own state changes (fault,
recovery, heartbeat) are keyed by crane. Keys order events within a topic only; dependencies across
topics are handled in section 4.

## 3. Idempotency: exactly-once effects on at-least-once delivery

**Decision.** A `processed_events` table keyed by `eventId`, written **in the same database
transaction** as the state change.

```
BEGIN
  INSERT INTO processed_events (event_id, ...) ON CONFLICT DO NOTHING   -- 0 rows? duplicate: stop
  check entity_versions (out-of-order rule)
  SAVEPOINT
    apply the event (handlers, row locks)
  RELEASE / ROLLBACK TO SAVEPOINT on business rejection
  UPDATE processed_events SET outcome = ...
COMMIT
then: commit the Kafka offset, update metrics, push SSE
```

**Why it works.** The ledger row and the state change commit or roll back together:
- crash before commit → both gone → Kafka redelivers → processed once;
- crash after commit but before the offset commit → redelivered → `ON CONFLICT DO NOTHING` inserts
  nothing → skipped;
- two copies racing on different threads → the second insert waits on the first transaction's row
  lock and then sees the conflict.

**Why not Kafka transactions / exactly-once semantics?** Kafka EOS covers Kafka-to-Kafka
pipelines. Our side effect is a PostgreSQL write; the database is the only place that can make
"state changed" and "event recorded" atomic. This is the transactional-inbox pattern.

**Rejections use a savepoint.** A business rejection (say, gate-out of a container that is not in
the yard) is detected after some reads and possibly some writes. Rolling back to the savepoint
removes partial changes but keeps the `processed_events` row, so the event is recorded as REJECTED
and dead-lettered exactly once.

**Cost.** One extra insert per event and a table that grows. A scheduled job deletes ledger rows
older than two days (configurable). A duplicate arriving after that would be processed again;
redelivery happens within seconds or minutes, so two days is far outside any realistic window.

## 4. Out-of-order events

**Rule.** For each entity (container, crane, vessel) the core stores the `occurredAt` of the last
applied event in `entity_versions`. An incoming event whose `occurredAt` is **strictly earlier** is
recorded as `IGNORED_STALE` and changes nothing. Equal timestamps are applied. Rejected and stale
events do not move the stored time.

**Why "ignore" rather than reorder.** Waiting for a missing earlier event needs a buffer, a timeout
and a policy for when it never arrives. For state that is overwritten by the next event (crane
status, a container's slot) the newest event is the truth, so an older one is genuinely obsolete.
A stale event is not an error, so it is not dead-lettered; it is counted and shown in the event
stream so it stays visible.

**Cross-topic order, and how the end-to-end test caught it.** Kafka orders within one partition of
one topic, not across topics. Two cases matter:

1. *Events of one container on different topics* (GATE_IN on gate, YARD_MOVE on yard). In normal
   operation they are minutes of simulated time apart (seconds of real time), so they arrive in
   order. If they ever did not, YARD_MOVE would wait out the grace period below and, failing that,
   be rejected as "unknown container" rather than corrupting state. The simulator publishes its opening inventory in phases with a pause between
   topics for the same reason.
2. *Different containers sharing a stack.* A pick-up (GATE_OUT, gate topic) or a load (CRANE_MOVE,
   crane topic) can remove a box from a stack in the same second that a YARD_MOVE (yard topic) puts
   another box on it. The core may apply them in either order. My first version validated the exact
   reported tier and the end-to-end run showed dozens of false SLOT_OCCUPIED / SLOT_FLOATING
   rejections, which then cascaded into "container not in yard". The fix changed the contract, see
   section 5: a placement goes on top of the reported stack. Both orders now converge to the same
   physical yard (`CrossTopicConvergenceIT`), and the smoke test checks that normal operation
   dead-letters nothing and that the core's yard matches the simulator's block by block.

3. *An event that depends on another entity's event on another topic.* A CRANE_MOVE (crane topic,
   keyed by container) needs the vessel to be berthed, but BERTH_ASSIGNED travels on the berth topic
   keyed by vessel. The second end-to-end run caught one move consumed a few milliseconds before its
   berth assignment, and it was dead-lettered as VESSEL_STATE_CONFLICT although nothing was wrong.

**Grace period for "not yet" rejections.** Each `RejectReason` says whether an event on another
topic could still make the event valid (`mayResolve`: unknown vessel, vessel not berthed, crane not
assigned, container not in the yard...) or not (bad check digit, malformed, slot outside the
layout). For the first kind the listener does not dead-letter at once: the processor throws
`DeferredEventException`, the whole transaction rolls back (including the idempotency claim), and
the listener tries again after 100, 200, 400, 800 ms, 1.6 s, 2 s (about 5 s in all, configurable as
`portsync.consumer.grace-retries` and `grace-backoff`). Only the final attempt dead-letters. Each
retry is counted in `portsync_events_deferred_total{reason}`, so a dependency that is always late
shows up on a graph instead of hiding.

- *Why it cannot deadlock.* There is one listener container per topic and three threads per
  container, so every thread owns one partition of one topic. A thread waiting for a berth event
  only holds up its own crane partition, never the berth partition that carries what it waits for.
- *Why block the partition rather than skip ahead.* Later events for the same container (its yard
  move, its gate-out) sit behind it in the same partition, and they need it applied first. Blocking
  keeps each entity's order intact.
- *Cost.* A genuinely impossible event of a resolvable kind (a gate-out for a container that never
  existed) reaches the DLQ about 5 seconds late, and holds its partition for those 5 seconds.
  `CrossTopicConvergenceIT` sends a crane move before its berth assignment and checks it is applied,
  not dead-lettered; `TerminalEventListenerTest` pins the retry sequence.

For a production system the more general remedies are one topic per aggregate, or a separate
delayed-retry topic so a waiting event does not hold its partition.

## 5. Yard invariants

| Invariant | Enforced by |
|---|---|
| One container per slot | Primary key on `(block, row_no, bay_no, tier)`; a placement always takes the first free tier |
| A container is in at most one slot | Partial unique index on `yard_slots(container_number)`; a move is "remove then place" |
| No container above an empty tier | Placement goes on top of the stack, under a `SELECT ... FOR UPDATE` lock on the whole stack |
| Removing a box lets the boxes above drop | `StackRules.removeAndSettle`, recorded as REHANDLE in the container history |
| GATE_OUT (pick-up) and LOAD free the slot | `YardService.remove` |

**Placement contract.** YARD_MOVE names a slot, but the core treats the tier as a report, not an
order: physically a straddle carrier can only set a box down on top of a stack, so that is where the
core puts it. If the stack is taller or shorter than the report assumed (section 4, case 2), the
container history notes "reported tier N" and the counter `portsync_yard_tier_adjustments` goes up.
What is still rejected: a slot outside the layout (`SLOT_INVALID`) and a stack that is already four
high (`STACK_FULL`). Trade-off: the core no longer catches an equipment system that reports a wrong
tier; it records it instead. In return the yard converges whatever the consumption order.

**Concurrency.** Three consumer threads can touch the yard at once. Every write locks the four tier
rows of the stack first; a move locks both stacks in a fixed order (block, row, bay) so two
transactions can never deadlock on each other. The integration test reports two containers into
the same slot concurrently and checks that one lands on top of the other, never in the same slot.

**A subtle one: settle order.** When a container is removed from under others, the boxes above move
down. The unique index is checked per statement, so writing "box B to tier 1" while B still sits at
tier 2 would briefly put B in two slots and fail. Changed slots are cleared first and refilled
second; `YardServiceTest` pins this order.

**Audited in production too.** `InvariantMonitor` runs the invariant queries every 30 s and exports
the violation count to Prometheus (`portsync_yard_invariant_violations`, should be 0). The same
audit is at `GET /api/ops/invariants`.

**Same rules in two places, written once.** The simulator keeps its own in-memory yard to choose
valid slots. Both it and the core use `StackRules` from `portsync-events`, so they cannot disagree.

## 6. Dead-letter queue

**Business rejections** (bad check digit, impossible move, unknown equipment...) are handled by the
processor: the original record value is forwarded **byte for byte** to `terminal.dlq` with headers
`portsync-dlq-reason`, `portsync-dlq-detail` and the source topic/partition/offset. The send is
synchronous inside the transaction: if Kafka does not acknowledge it, the transaction rolls back and
the record is retried, so a rejection is never lost. The consumer never crashes on bad data.
Rejections that another topic's event could still fix are dead-lettered only after the grace period
of section 4.

**Unreadable records** (not JSON, missing envelope fields) are dead-lettered as `MALFORMED_EVENT`
before any database work.

**Unexpected exceptions** (database down, a bug) are retried with exponential back-off by Spring
Kafka's `DefaultErrorHandler` (5 attempts, up to 5 s apart), then dead-lettered as
`PROCESSING_FAILED`. Trade-off: blocking a partition forever preserves order but stops all progress
on it; giving up after a bounded number of retries keeps the system moving and leaves the record
recoverable from the DLQ.

**DLQ materialisation.** A separate listener copies `terminal.dlq` into `dlq_events` for the API.
The unique key on the source coordinates makes re-reading the topic harmless.

## 7. SLA rules and alerts

Five rules, each a pure function `SlaSnapshot → List<Breach>`, evaluated every 5 seconds of wall
time against **simulated** time. Thresholds are in `application.yml` and validated at startup.

| Rule | Default | Notes |
|---|---|---|
| Crane productivity | < 20 moves/h over 60 min | Only cranes at a berth for at least a full window; faulted cranes skipped (covered by the fault rule, one root cause should not page twice) |
| Crane faulted | > 15 min | Critical |
| Vessel waiting for berth | > 90 min | |
| Truck turn time | > 45 min | Evaluated on trucks still inside, so the alert fires while something can still be done |
| Yard block utilisation | > 85% | Critical from 95% |

**De-duplication** by `(rule, entity)`: a partial unique index allows one active (OPEN or
ACKNOWLEDGED) alert per pair, and `AlertReconciler` turns a repeated breach into a refresh of the
existing alert, never a second one. The index also makes it race-safe if two evaluations overlap.

**Auto-resolve.** An active alert whose condition is no longer present is resolved with resolution
AUTO.

**Manual resolve does not hide problems.** If an operator resolves an alert whose condition still
holds, the next evaluation opens a new one. Acknowledging is the way to say "I know, I'm on it".

## 8. Simulated clock

**Simulator.** `now = start + wallElapsed x speedFactor` (60 by default). The engine is a
discrete-event simulation: everything that happens is an action on one agenda ordered by simulated
time, so events for any entity are produced in time order. The random generator is seeded, so a run
is reproducible.

**Core.** It does not share the simulator's clock. It keeps a *watermark*, the newest `occurredAt`
it has seen, and extrapolates at the configured speed, but never more than 10 simulated minutes past
the last event:

```
now = watermark + min((wallNow - wallTimeWhenWatermarkSeen) x speedFactor, 10 min)
```

- Crane heartbeats every 5 simulated minutes keep the watermark fresh.
- Old events never move it back; `now()` is monotonic.
- If the simulator is paused or stops, simulated time stops too, so SLA timers do not run away.

**Why.** With 60x, a 15-minute SLA breach is visible 15 seconds into a demo. Using event time rather
than the core's wall clock is the standard stream-processing answer to "whose clock?".

## 9. SSE rather than WebSockets

The dashboard only needs **server to browser** updates; actions (acknowledge, resolve) are ordinary
POSTs. Server-Sent Events are plain HTTP: they pass through the nginx proxy with buffering turned
off, need no extra protocol, and are easy to observe with `curl`. The browser's `EventSource`
reconnects on network errors; the dashboard wraps it to also reconnect when the server returns an
error status, with exponential back-off, and reloads everything after reconnecting so missed
messages do not matter.

**Notify after commit only.** Live messages are sent by `@TransactionalEventListener(AFTER_COMMIT)`,
so a browser never sees a change that was rolled back.

**Never block the consumer on a browser.** `SseHub` hands messages to a single sender thread through
a bounded queue; when it is full, messages are dropped and counted. The stream carries *what
changed*, and the page fetches the current state, so a dropped message costs at most a stale view
until the next one.

**Cost.** One open HTTP connection per tab, and if this service were scaled out, each instance would
only know about the events it processed itself; a shared broadcast (for example, every instance
consuming a notifications topic) would be needed.

## 10. JDBC rather than JPA

Every write path takes explicit row locks and some rely on `ON CONFLICT` clauses and partial
indexes. Writing the SQL directly with Spring's `JdbcClient` keeps each statement visible and
explainable line by line, avoids lazy-loading surprises in a transactional consumer, and makes the
locking strategy obvious. Cost: mapping code is hand-written.

## 11. Observability

- **Health.** `/actuator/health/liveness` (the process is alive) and
  `/actuator/health/readiness` (database **and** Kafka reachable). Kafka has no built-in Spring Boot
  health indicator, so `KafkaHealthIndicator` describes the cluster with a 3-second timeout. Kafka
  being down makes the instance not-ready but leaves liveness UP: a restart would not fix a broker
  outage.
- **Metrics** (Micrometer → Prometheus): events consumed by type and outcome, DLQ events by
  reason, consumer lag per topic (measured from the broker with the AdminClient), active alerts by
  rule, alerts raised, processing time and end-to-end latency histograms, yard invariant
  violations, plus JVM, HTTP and Kafka client metrics.
- **Logs.** With the `json` profile (on in Compose), logs are structured JSON (Logstash layout).
  Every line written while an event is processed carries `eventId`, `eventType`, `entityId`,
  `topic`, `partition` and `offset` from the MDC.
- **Grafana.** One dashboard is provisioned from `infra/grafana`.

## 12. ISO 6346

Letter values start at A=10 and skip multiples of 11; each of the first ten characters is weighted
by 2^position; the check digit is the sum mod 11, with 10 becoming 0. Validation and generation live
in `ContainerNumber` (Java) and `iso6346.ts` (dashboard, so a typo is explained before any request).

**The standard has blind spots.** Because some letter values differ by exactly 11 (C=13, M=24),
a few single-letter substitutions in the owner code still validate, for example `MSQU3054383`.
Every single-*digit* change is always detected. The tests assert exactly which four mutations of
`CSQU3054383` slip through rather than claiming "any change fails".

## 13. What I would change for production

- Run more than one core instance (consumer group rebalancing already works) and move SSE fan-out
  to a shared channel.
- Put each aggregate's events on one topic, or replace the in-place grace period with a delayed
  retry topic, to remove the cross-topic ordering limit without holding a partition.
- Schema registry with Avro or Protobuf instead of JSON plus a JSON Schema file.
- Authentication on the API and the fault injection endpoints (here they are open on purpose).
- Partition `processed_events` and `container_history` by time.
- Kafka replication factor 3 and `min.insync.replicas=2`.
