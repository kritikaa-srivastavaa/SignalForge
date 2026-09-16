# SignalForge
Distributed event-processing and incident-detection platform built with Java, Spring Boot, Kafka, PostgreSQL, and Docker.

## Local observability

1. From the repository root, run `docker compose up -d` to start PostgreSQL, Kafka, Prometheus, and Grafana.
2. In another terminal, run `cd backend` then `.\mvnw.cmd '-Dspring-boot.run.jvmArguments=-Duser.timezone=Asia/Kolkata' spring-boot:run` to start Spring Boot on host port 8080 with the PostgreSQL timezone workaround.
3. Open [Prometheus targets](http://localhost:9090/targets) and confirm the `signalforge` target is **UP**. Prometheus scrapes `host.docker.internal:8080/actuator/prometheus` every 15 seconds because the backend runs on Windows, outside Docker.
4. Open [Grafana](http://localhost:3000), sign in with local-only credentials **admin / admin** (skip the initial password-change prompt if shown), and open **SignalForge Overview** under Dashboards. The Prometheus datasource and dashboard are provisioned automatically; Grafana connects to `http://prometheus:9090` inside Docker.

The dashboard refreshes every 5 seconds and defaults to the last 15 minutes. Send events through the existing API and allow at least two scrapes for rate/increase panels to populate. Totals are estimated counter increases over the selected range, not database row counts; newly created series may miss their first increment. Unused aggregate business metrics display zero, while grouped panels may show No data until a labeled series exists. Check the Prometheus target before interpreting zero as inactivity.

Prometheus and Grafana use named volumes. Prometheus uses its default 15-day retention. Do not use `docker compose down -v` if you want to preserve local database and monitoring data.
