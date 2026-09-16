# SignalForge
Distributed event-processing and incident-detection platform built with Java, Spring Boot, Kafka, PostgreSQL, and Docker.

## Development modes

**Full Docker stack:** Stop any host backend using port 8080, then run `docker compose up -d --build` from the repository root. The image builds with Maven/Java 21 inside Docker; no host Java or Maven is needed. Compose injects `jdbc:postgresql://postgres:5432/signalforge`, `kafka:29092`, and the JVM timezone `Asia/Kolkata`. The backend waits for PostgreSQL's `pg_isready` and Kafka's broker API healthcheck before starting. Its own healthcheck requests `/actuator/health`; dependency health gates startup, not ongoing availability. Flyway runs normally at application startup.

**Backend on Windows, infrastructure in Docker:** Run `docker compose stop backend` to free port 8080, then `docker compose up -d postgres kafka prometheus grafana`. Run `cd backend` and `.\mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Duser.timezone=Asia/Kolkata'`. The existing application defaults use PostgreSQL at `localhost:5433` and Kafka at `localhost:9092`; Kafka advertises separate internal and host listeners. Prometheus remains configured for `backend:8080`, so it will report the backend target DOWN in host mode; host-backend observability is not automatically supported.

Run the full tests separately from the image build: from `backend`, use `.\mvnw.cmd '-Duser.timezone=Asia/Kolkata' verify` with PostgreSQL and Kafka running. The Docker image build skips test execution because integration tests require those external services.

Useful commands: `docker compose ps`, `docker compose logs -f backend`. Logs go to the container console. Kafka retains its existing local-development storage configuration: broker records are in the container's `/tmp/kafka-logs`, so recreating the Kafka container resets its records and offsets. Durable Kafka storage is not introduced here.

## Local observability

1. Check [backend health](http://localhost:8080/actuator/health) for status `UP` (backend URL: http://localhost:8080).
2. Open [Prometheus](http://localhost:9090) or [its targets page](http://localhost:9090/targets) and confirm `signalforge` is **UP** in full Docker mode. It scrapes `backend:8080/actuator/prometheus` every 15 seconds over the Compose network.
3. Open [Grafana](http://localhost:3000), sign in with local-only credentials **admin / admin** (skip the initial password-change prompt if shown), and open **SignalForge Overview** under Dashboards. The Prometheus datasource and dashboard are provisioned automatically; Grafana connects to `http://prometheus:9090` inside Docker.

The dashboard refreshes every 5 seconds and defaults to the last 15 minutes. Send events through the existing API and allow at least two scrapes for rate/increase panels to populate. Totals are estimated counter increases over the selected range, not database row counts; newly created series may miss their first increment. Unused aggregate business metrics display zero, while grouped panels may show No data until a labeled series exists. Check the Prometheus target before interpreting zero as inactivity.

Prometheus and Grafana use named volumes. Prometheus uses its default 15-day retention. Do not use `docker compose down -v` if you want to preserve local database and monitoring data.

## Frontend development

Use Node.js 24 LTS (or 22.12+) and start the backend stack with `docker compose up -d --build`. The frontend runs separately; it is not a Compose service.

```powershell
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. Overview shows actual totals and recent records; Events and Incidents support exact-match filters and server pagination. Apply or clear filters to reset to page 1. Severity accepts arbitrary values, with HIGH/MEDIUM/LOW suggestions. Incident lifecycle actions are not included yet.

The frontend calls http://localhost:8080 directly using `VITE_API_BASE_URL` (that is also the default). Copy `frontend/.env.example` to `frontend/.env` to change it, then restart Vite. No development proxy is used. Backend CORS permits only `http://localhost:5173` to GET `/events` and `/incidents`; a different frontend origin needs a deliberate CORS update. Do not use `127.0.0.1:5173` as the browser origin for this configuration.

Run `npm run test` for behavioral tests and `npm run build` for strict TypeScript checking and a production build. No lint script is configured. Counts are snapshots refreshed through the Refresh button; Grafana remains the metrics dashboard.
