docker compose up -d --build > /dev/null 2>&1
for i in $(seq 1 240); do curl -fsS http://localhost:8080/actuator/health/readiness > /dev/null && break; sleep 1; done
for i in $(seq 1 120); do curl -fsS http://localhost:8081/api/simulation/status | jq -e '.running' > /dev/null 2>&1 && break; sleep 1; done
date -u; sleep 120
curl -fsS "localhost:8080/api/dlq?limit=500" | jq -c '.[] | {deadLetteredAt, type, entityId, reason, detail, sourceTopic, sourcePartition, sourceOffset, raw: (.rawValue|fromjson|{occurredAt, payload})}'
for e in $(curl -fsS "localhost:8080/api/dlq?limit=500" | jq -r '.[].entityId' | sort -u | head -8); do echo "== $e"; curl -fsS "localhost:8080/api/containers/$e" | jq -c '.history[] | [.occurredAt, .type, .description]' 2>/dev/null; done
curl -fsS "localhost:8080/api/events?limit=1000" | jq -c '[.[] | select(.outcome != "APPLIED")] | .[] | {type, entityId, outcome, reason, occurredAt, topic, partition}' | head -30
docker compose logs --no-color portsync-core 2>&1 | grep -E '"level":"(WARN|ERROR)"' | head -30 | cut -c1-600
docker compose down -v > /dev/null 2>&1
