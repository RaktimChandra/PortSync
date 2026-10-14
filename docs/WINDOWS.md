# Running PortSync on Windows 11

Tested setup: Windows 11, Docker Desktop with the WSL 2 backend, VS Code. Commands are for
PowerShell unless a step says otherwise. Run them one at a time.

## Option A: everything in Docker (recommended)

You only need Docker Desktop running and Git.

**Terminal 1 (PowerShell)**

```powershell
git clone https://github.com/RaktimChandra/PortSync.git
```

```powershell
cd PortSync
```

```powershell
docker compose up -d --build
```

The first build takes several minutes (Maven and npm download dependencies inside Docker). Check
that everything is up:

```powershell
docker compose ps
```

All of `kafka`, `postgres`, `portsync-core`, `simulator` and `frontend` should say `healthy`.
Open http://localhost:3000.

To follow the core's logs:

```powershell
docker compose logs -f portsync-core
```

To stop (keeps data):

```powershell
docker compose down
```

To stop and delete all data:

```powershell
docker compose down -v
```

## Option B: infrastructure in Docker, apps from your machine

Useful for debugging in VS Code. Needs JDK 21 (for example Eclipse Temurin 21) and Node.js 22.

**Terminal 1 (PowerShell): infrastructure**

```powershell
docker compose up -d kafka postgres
```

**Terminal 2 (PowerShell): build once, then start the core service**

```powershell
.\mvnw.cmd install -DskipTests
```

```powershell
.\mvnw.cmd -pl portsync-core spring-boot:run
```

Wait for `Started PortSyncCoreApplication`.

**Terminal 3 (PowerShell): simulator**

```powershell
.\mvnw.cmd -pl simulator spring-boot:run
```

**Terminal 4 (PowerShell): dashboard**

```powershell
cd frontend
```

```powershell
npm install
```

```powershell
npm run dev
```

Open http://localhost:5173 (Vite proxies `/api` to the core on port 8080).

Only Kafka and PostgreSQL run in Docker here (starting Prometheus would also start the containerised
core, because it depends on it). Metrics are still at http://localhost:8080/actuator/prometheus; use
Option A for the Prometheus and Grafana demo.

## Tests

```powershell
.\mvnw.cmd test
```

Runs unit tests only, no Docker needed.

```powershell
.\mvnw.cmd verify
```

Also runs the Testcontainers integration tests. Docker Desktop must be running.

```powershell
cd frontend
npm test
```

## Load test

With the stack from Option A running:

```powershell
docker compose --profile loadtest run --rm loadtest
```

The report is printed and saved under `loadtest-results\`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `port is already allocated` | Something else uses 8080, 8081, 3000, 3001, 5432, 9090 or 9094. Stop it, or change the left-hand port in `docker-compose.yml` |
| `mvnw.cmd` cannot download Maven | A proxy or firewall blocks repo.maven.apache.org; configure `%USERPROFILE%\.m2\settings.xml` |
| Integration tests fail with "Could not find a valid Docker environment" | Start Docker Desktop; in Settings > General, enable "Expose daemon on tcp://localhost:2375 without TLS" only if your setup needs it |
| Dashboard says "Reconnecting" | The core is restarting or down: `docker compose ps`, then `docker compose logs portsync-core` |
| Shell scripts fail with `\r` errors | They must have LF line endings. `.gitattributes` enforces this on checkout; if you edited them, convert back to LF |
