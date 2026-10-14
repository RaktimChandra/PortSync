#!/usr/bin/env bash
# End-to-end smoke test of the full Docker Compose stack. Used by CI; runs on Linux/macOS/WSL.
# Brings the stack up, waits for the simulator's events to flow through Kafka into the core,
# injects faults, and checks the API, the dashboard proxy, Prometheus and Grafana.
set -euo pipefail

CORE=http://localhost:8080
SIM=http://localhost:8081

wait_for() { # url, seconds
  local url=$1 limit=$2
  for _ in $(seq 1 "$limit"); do
    if curl -fsS "$url" > /dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "Timed out waiting for $url" >&2
  return 1
}

check() { # description, command...
  local what=$1; shift
  if "$@"; then echo "PASS  $what"; else echo "FAIL  $what" >&2; exit 1; fi
}

echo "== Starting stack"
docker compose up -d --build

wait_for "$CORE/actuator/health/readiness" 240
wait_for "$SIM/actuator/health" 180

echo "== Waiting for the opening inventory and live events"
for _ in $(seq 1 120); do
  processed=$(curl -fsS "$CORE/api/overview" | jq '.eventsProcessed')
  [ "$processed" -gt 1500 ] && break
  sleep 1
done
check "core processed simulator events ($processed)" test "$processed" -gt 1500
check "readiness includes db and kafka" bash -c "curl -fsS $CORE/actuator/health | jq -e '.components.db.status==\"UP\" and .components.kafka.status==\"UP\"' > /dev/null"
check "yard invariants hold" bash -c "curl -fsS $CORE/api/ops/invariants | jq -e '.consistent' > /dev/null"
check "yard has containers" bash -c "curl -fsS $CORE/api/overview | jq -e '.yardUtilisation > 0.3' > /dev/null"

echo "== Waiting for the simulator to finish its opening inventory"
for _ in $(seq 1 120); do
  curl -fsS "$SIM/api/simulation/status" | jq -e '.running' > /dev/null 2>&1 && break
  sleep 1
done
check "simulator running" bash -c "curl -fsS $SIM/api/simulation/status | jq -e '.running' > /dev/null"

echo "== Letting the terminal run for 60 s (an hour of simulated time)"
sleep 60
check "normal operation produced no rejections (DLQ empty)" bash -c "curl -fsS '$CORE/api/dlq' | jq -e 'length == 0' > /dev/null"
check "SLA evaluation is running" bash -c "curl -fsS $CORE/api/ops/runtime | jq -e '.slaLastEvaluatedAt != null' > /dev/null"

echo "== Fault injection"
curl -fsS -X POST "$SIM/api/faults/invalid-container" | jq -c '{expectedOutcome, events: [.events[].entityId]}'
curl -fsS -X POST "$SIM/api/faults/impossible?kind=GATE_OUT_NOT_IN_YARD" > /dev/null
curl -fsS -X POST "$SIM/api/faults/impossible?kind=MALFORMED_JSON" > /dev/null
curl -fsS -X POST "$SIM/api/faults/duplicates?count=10" > /dev/null
curl -fsS -X POST "$SIM/api/faults/out-of-order" | jq -c '{expectedOutcome}'
curl -fsS -X POST "$SIM/api/faults/cranes/QC01/fault?code=SMOKE_TEST" > /dev/null
sleep 8
check "invalid check digit dead-lettered" bash -c "curl -fsS '$CORE/api/dlq?limit=200' | jq -e 'map(.reason) | index(\"INVALID_CHECK_DIGIT\") != null' > /dev/null"
check "gate-out of unknown container dead-lettered" bash -c "curl -fsS '$CORE/api/dlq?limit=200' | jq -e 'map(.reason) | index(\"CONTAINER_NOT_IN_YARD\") != null' > /dev/null"
check "malformed record dead-lettered" bash -c "curl -fsS '$CORE/api/dlq?limit=200' | jq -e 'map(.reason) | index(\"MALFORMED_EVENT\") != null' > /dev/null"
check "stale event ignored" bash -c "curl -fsS '$CORE/api/events?limit=1000' | jq -e 'map(.outcome) | index(\"IGNORED_STALE\") != null' > /dev/null"
check "crane QC01 shows FAULTED" bash -c "curl -fsS $CORE/api/cranes | jq -e '.[] | select(.id==\"QC01\") | .status==\"FAULTED\"' > /dev/null"
check "invariants still hold" bash -c "curl -fsS $CORE/api/ops/invariants | jq -e '.consistent' > /dev/null"

echo "== Core and simulator agree on the yard"
curl -fsS -X POST "$SIM/api/simulation/pause" > /dev/null
sleep 6
core_yard=$(curl -fsS "$CORE/api/yard/blocks" | jq -c 'map({(.block): .used}) | add')
sim_yard=$(curl -fsS "$SIM/api/simulation/status" | jq -c '.terminal.yardOccupancy')
echo "core: $core_yard  simulator: $sim_yard"
check "per-block occupancy identical in core and simulator" test "$core_yard" = "$sim_yard"
curl -fsS -X POST "$SIM/api/simulation/resume" > /dev/null

echo "== Edges of the stack"
check "dashboard served by nginx" curl -fsS -o /dev/null http://localhost:3000/
check "API reachable through the dashboard proxy" curl -fsS -o /dev/null http://localhost:3000/api/overview
check "Prometheus scrapes portsync-core" bash -c "sleep 10; curl -fsS 'http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22portsync-core%22%7D' | jq -e '.data.result[0].value[1]==\"1\"' > /dev/null"
check "custom metrics exported" bash -c "curl -fsS $CORE/actuator/prometheus -o /tmp/portsync-metrics.txt && grep -q portsync_events_consumed_total /tmp/portsync-metrics.txt"
check "Grafana healthy with provisioned dashboard" bash -c "curl -fsS http://localhost:3001/api/dashboards/uid/portsync-ops | jq -e '.dashboard.title' > /dev/null"
check "JSON logs carry eventId" bash -c "docker compose logs portsync-core | grep -m1 '\"eventId\"' > /dev/null"

echo "Smoke test passed."
