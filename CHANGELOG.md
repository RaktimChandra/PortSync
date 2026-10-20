# Changelog

## [1.1.1] — 2026-10-21 — the simulator pause race

### Fixed
- **Pausing the simulator waits for a tick already under way.** `POST /api/simulation/pause`
  stopped the clock and answered at once, while a tick that had checked the clock just before
  could still change the yard and publish events; the status the pause returned was then one
  step behind what the core received. Found by TestHarness's core/simulator yard comparison
  (the core one container ahead, twice). The tick and `SimulationRunner.pause()` now share the
  simulation's lock (`SimulationRunnerTest`).

### Changed
- CI uses `actions/upload-artifact@v7`; v4 ran on the deprecated Node.js 20.
- Version 1.1.1 for every Maven module and the dashboard.

## [1.1.0] — 2026-10-20 — accessibility

### Fixed
- **Colour contrast (WCAG 2.1 AA)** in the light theme, found by the axe-core scans that
  [TestHarness](https://github.com/RaktimChandra/TestHarness)'s PortSync adapter runs against
  the live dashboard, plus one more found by auditing every colour pair:
  - the warning tone (`--signal`) on white was 4.21:1 → `#965b00`, 5.54:1;
  - a berthed vessel's code over the quay progress fill was 3.03:1 (2.97:1 in the dark theme) →
    labels over the fill use `--ink`, 8.90:1 (dark: 6.05:1);
  - the primary button's text on the light amber was 4.41:1 → new `--on-signal` token, white in
    the light theme (5.54:1), unchanged in the dark theme (9.14:1).

### Added
- `frontend/src/test/contrast.test.ts`: WCAG contrast of every colour pair the dashboard
  draws, read from `styles.css` for the dark theme and both light-theme blocks; fails on the
  previous palette.
- `frontend/src/components/QuaySchematic.test.tsx`.

### Changed
- Version 1.1.0 for every Maven module and the dashboard (was 1.0.0-SNAPSHOT / 1.0.0).

## [1.0.0] — 2026-10-14 — first release

- Simulator on a fast clock publishing vessel, crane, gate and yard events to Kafka (KRaft).
- `portsync-core`: exactly-once application of events to PostgreSQL through an idempotency
  ledger, dead-lettering with reasons, a documented out-of-order rule, yard stacking invariants,
  five SLA rules on simulated time, alert lifecycle, REST API with one error body, Server-Sent
  Events, Kafka health, consumer lag and an invariant audit.
- React dashboard: overview with KPIs and the quay schematic, berths and cranes, yard stack grid,
  container lookup with ISO 6346 validation, alerts, event stream and DLQ.
- Prometheus and a provisioned Grafana dashboard; fault-injection endpoints; a Kafka load
  generator (`loadtest`, burst and paced runs); Docker Compose stack; CI. Measured results in [RESULTS.md](RESULTS.md).
