# Local development

## Prerequisites

Docker Desktop with Linux containers and Compose. Host development additionally requires Java 21, Node.js 24 LTS (`>=22.12` supported), and Python 3.11+ for validation. Maven is supplied by the wrapper. Run commands from the repository root unless stated otherwise. PowerShell users should use `npm.cmd` if script execution policy blocks `npm.ps1`.

## Full stack

```powershell
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

Open UI `http://localhost:5173`, health `http://localhost:8080/actuator/health`, Prometheus `http://localhost:9090`, Grafana `http://localhost:3000`. Wait for frontend/backend/PostgreSQL/Kafka health. Grafana uses published local defaults `admin` / `admin` and provisions SignalForge Overview automatically. Those defaults and the database defaults are not safe for public deployment.

No `.env` is required. For optional settings copy `.env.example` to ignored `.env`. On a fresh database, create an ADMIN via opt-in `SIGNALFORGE_BOOTSTRAP_ENABLED=true`, a new email, and a strong locally supplied password. Disable bootstrap and remove the password after successful creation. An existing non-admin identity cannot be claimed by bootstrap; an existing ADMIN must use the audited Users workflow to promote it. Do not replace the current local owner's account or reset passwords for verification.

SMTP is disabled by default. See [mail configuration](ACCESS_REQUEST_EMAIL.md). Compose reads root `.env`; a host Java process instead needs corresponding process environment variables. Frontend `.env.example` configures Vite separately, and Vite configuration is compiled at build time.

## Host backend

```powershell
docker compose stop frontend backend
docker compose up -d postgres kafka prometheus grafana
cd backend
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Duser.timezone=Asia/Kolkata'
```

Defaults connect to PostgreSQL `localhost:5433` and Kafka `localhost:9092`. Keep the timezone workaround: `.mvn/jvm.config` configures Maven, Compose sets JAVA_TOOL_OPTIONS, and the explicit forked application argument above preserves it. Prometheus targets the Docker backend DNS name; host-mode scraping is not configured automatically.

## Host frontend

With backend running, free port 5173 using `docker compose stop frontend`, then:

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```

Use `http://localhost:5173`, not `127.0.0.1:5173`, because backend CORS permits the localhost origin. Host Vite defaults to `http://localhost:8080`; copy `frontend/.env.example` if changing it. The Docker UI uses same-origin `/api` instead.

## Verification

Start PostgreSQL and Kafka first. From `backend`:

```powershell
.\mvnw.cmd '-Duser.timezone=Asia/Kolkata' verify
```

Tests use real configured PostgreSQL and Kafka where needed; no H2/Testcontainers. Integration fixtures are isolated and cleaned up. Docker image builds skip tests because external services are unavailable during image build; run verification separately.

From `frontend`:

```powershell
npm.cmd run test
npm.cmd run build
```

From repository root:

```powershell
python -B -m unittest discover -s scripts/validation -p "test_*.py" -v
docker compose config --quiet
```

Validation unit tests are safe and require no personal credentials. Live load/failure scripts are separate, require authorized account credentials through environment variables, and can create events/incidents or stop services when explicitly opted in. See [validation evidence and reproduction](validation/VALIDATION.md); do not run destructive experiments against valuable data.

## Safe shutdown

Use `docker compose stop` to stop services and retain containers and data. `docker compose down` preserves named PostgreSQL/monitoring volumes but removes Kafka's container-local records. Do not use `down -v` as normal setup. To return from host mode, stop host Java/Vite and run `docker compose up -d --build`. Inspect logs with `docker compose logs -f backend`; never copy secrets into reports.
