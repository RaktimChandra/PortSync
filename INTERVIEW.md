# Talking about PortSync

## The two-minute version

PortSync is a monitoring system for a simulated container terminal. I built it to learn how
terminal operations software has to behave when the data coming in is messy.

There are three parts. A simulator runs a terminal on a fast clock, sixty times real time: vessels
arrive and berth, six quay cranes discharge and load, trucks drop off exports and collect imports,
and cranes break down. Every one of those things is published as an event to Kafka, on four topics,
keyed by the container, crane or vessel it is about, so each entity's events stay in order.

The core service is Spring Boot. It consumes the events and keeps the terminal's state in
PostgreSQL: where every container sits in the yard, which vessel is at which berth, which cranes are
down. The part I spent most time on is correctness. Kafka delivers at least once, so every event is
recorded in a processed-events table in the same transaction as the state change, which makes
duplicates harmless. An event that is older than the last one applied to the same entity is ignored
and counted as stale. Impossible events, like a bad ISO 6346 check digit or a gate-out for a
container that is not in the yard, go to a dead-letter topic with a reason instead of crashing the
consumer. The yard invariants (one box per slot, no box in two slots, nothing floating) are enforced
with row locks and checked continuously.

On top of that, five SLA rules run every few seconds against simulated time and raise alerts that
are de-duplicated and clear themselves. A React dashboard shows it all live over Server-Sent
Events, and there are Prometheus metrics, JSON logs with the event id on every line, and a Grafana
dashboard.

It is tested with unit tests, Testcontainers integration tests against real Kafka and Postgres,
REST Assured, and Vitest, and GitHub Actions runs all of it plus an end-to-end smoke test of the
Docker Compose stack on every push.

## Likely questions, with honest answers

**1. How do you guarantee an event is processed exactly once?**
I don't get exactly-once delivery; Kafka gives at-least-once. I get exactly-once *effects*: the
first thing the transaction does is insert the eventId into `processed_events` with
`ON CONFLICT DO NOTHING`. If no row is inserted, it is a duplicate and I stop. The insert and the
state change commit together, so a crash at any point either keeps both or loses both, and the
redelivered record behaves correctly. `IdempotencyIT` sends the same event five times and checks the
state changed once.

**2. Why not Kafka's exactly-once semantics?**
Those cover read-from-Kafka, write-to-Kafka. My side effect is a database write, and only the
database can make "state changed" and "event recorded" atomic. It is the transactional inbox pattern.

**3. What happens when events arrive out of order?**
Within one partition they can't, because I key by entity. Across topics they can. I store the time
of the last applied event per entity; anything strictly older is recorded as `IGNORED_STALE` and
does nothing. Equal timestamps are applied. The trade-off is that I don't reorder or buffer, which
is fine for state where the newest event wins, like a crane's status. Cross-topic *dependencies*
are a different problem, see 6b.

**4. Why did you key CRANE_MOVE by container and not by crane?**
Because a move changes the container's state, and one container can have several moves: discharged
by one crane, later loaded onto another ship by a different crane. Keyed by crane, those two moves
could sit on different partitions and the load could be processed before the discharge. Keyed by
container, every move of that box is on one partition, in order. The crane's own status changes
(fault, recovery, heartbeat) are keyed by crane. What keying can't do is order a container's crane
move against its gate or yard events, because those are on other topics; that's what the grace
period in question 6b is for.

**5. How do you stop two consumer threads corrupting the yard?**
Every yard write first locks the four rows of the stack with `SELECT ... FOR UPDATE`. A move locks
both stacks in a fixed order so two transactions can't deadlock. The database also backs it up: the
primary key means one container per slot, and a partial unique index means a container can only be
in one slot. `YardInvariantsIT` sends two containers to the same slot at the same time and checks
exactly one wins.

**6. Tell me about a bug you found.**
The best one was a design bug, found by my end-to-end smoke test, not a unit test. A truck picking
a box up (gate topic) and a straddle carrier putting another box on the same stack (yard topic)
can happen in the same second, and the core can apply them in either order. I originally validated
the exact tier in each YARD_MOVE, so whichever arrived "second" looked like a floating or occupied
slot and was dead-lettered, and later events for that box failed too. The fix was to change the
contract: a box is set down on top of the stack, and if that differs from the reported tier I record
it. Now both orders converge, there's an integration test for each order, and the smoke test checks
that normal operation produces zero dead letters and that the core's yard matches the simulator's.

Two smaller ones were also caught by tests. When a container is removed from under others, the boxes above drop a
tier. Writing "box B to tier 1" while B was still at tier 2 violated the unique index for a moment,
because Postgres checks it per statement. I clear the changed slots first and refill them second,
and a Mockito test pins that order. The second was in the simulator: the "out of order" fault picked
a time one hour before "now", but for containers from the opening inventory that could still be
*newer* than their last event, so the core would have applied it. The property test that compares
the stale time with each container's latest event caught it.

**6b. And a bug the end-to-end run found after that fix?**
The second run dead-lettered one crane move as "vessel not berthed" although the simulator was
right: the move is on the crane topic, the berth assignment on the berth topic, and the core
happened to read the move a few milliseconds first. I didn't want to hide it by loosening the rule,
so each rejection reason now says whether another topic's event could still fix it. For those,
the processor rolls the whole transaction back and the listener retries with back-off for about five
seconds before dead-lettering. Retries are counted in a metric so a dependency that's always late is
visible. It can't deadlock because each consumer thread owns one partition of one topic, so a
thread waiting for a berth event never holds the partition that carries it. An integration test
sends the move before the berth assignment and checks it's applied, and the smoke test checks the
DLQ stays empty under normal load.

**7. What goes to the dead-letter queue and what doesn't?**
Business rejections go there with a reason: bad check digit, unknown crane, crane faulted, container
not in the yard, full stack and so on, plus unreadable JSON. The original bytes are forwarded
unchanged with the reason in headers. A rejection that another topic could still fix waits out a
short grace period first. Stale events and duplicates do not go there; they are normal. Unexpected
exceptions, like the database being down, are retried with back-off and only dead-lettered after
five attempts.

**8. How do you know the consumer isn't falling behind?**
Consumer lag: I measure it from the broker with the AdminClient, latest offset minus committed
offset per partition, and export it as a Prometheus gauge. There is also an end-to-end latency
histogram from the Kafka record timestamp to the commit. Both are on the Grafana dashboard.

**9. What do readiness and liveness mean here?**
Liveness says the JVM is alive. Readiness says the database and Kafka are reachable; I wrote a Kafka
health indicator because Spring Boot doesn't have one. If Kafka goes down the service becomes
not-ready but stays live: restarting it wouldn't fix a broker outage, it would just add restarts.

**10. How do the SLA rules know what time it is?**
They use simulated time. The core keeps a watermark, the newest event time it has seen, and
extrapolates from it at the speed factor, capped at ten simulated minutes, so if the simulator stops,
time stops and no false alerts fire. Crane heartbeats every five simulated minutes keep it fresh.

**11. How are alerts de-duplicated?**
By rule and entity. A partial unique index allows one OPEN or ACKNOWLEDGED alert per pair, and the
reconciler turns a repeated breach into a refresh. If the condition clears, the alert resolves itself.
If someone resolves an alert while the problem is still there, a new one opens on the next evaluation,
so resolving can't hide a real problem.

**12. Why SSE and not WebSockets?**
The browser only needs to receive; actions are normal POSTs. SSE is plain HTTP, works through the
nginx proxy with buffering off, and is easy to debug with curl. Messages are sent only after the
transaction commits, and through a bounded queue so a slow browser can never block the Kafka
consumer.

**13. What would you need to change to run several instances of the core?**
Consumption already scales: the consumer group spreads partitions across instances, and correctness
lives in the database, not in memory. Two things would change: SSE fan-out, because each instance
only knows its own events, and the per-instance load test statistics. I'd publish change
notifications to a topic every instance consumes.

**14. What numbers did you measure, and on what?**
They are in RESULTS.md with the date, the machine and the command. The honest summary is that
processing is per-record transactional, so throughput is bounded by database round-trips; the
reported end-to-end latency under a burst is mostly time spent queued in Kafka, which the report
says explicitly.

**15. How does your CI map to TeamCity?**
Each GitHub Actions job is a build configuration: backend runs `mvn verify`, frontend runs the
TypeScript check, Vitest and the build. The end-to-end job depends on both, which is a snapshot
dependency, so together they form a build chain on one revision. The push and pull-request triggers
are VCS triggers, the runner is the agent, uploaded reports and jars are artifacts. CI.md has the
full table.

## If they ask "what would you do next?"

- One topic per aggregate, or a delayed retry topic instead of the in-place grace period, so a waiting event does not hold its partition.
- Avro or Protobuf with a schema registry instead of JSON plus a schema file.
- Batch commits of offsets and database writes to raise throughput, measured against the current numbers.
- Authentication on the API and fault injection.
- A browser end-to-end test of the dashboard with Playwright.
