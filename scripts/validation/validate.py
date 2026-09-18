"""Local Compose evidence runner. Failure experiments require --include-failures.
Run alone: concurrent application traffic makes count/offset assertions ambiguous.
No table cleanup, offset changes, topic deletion, or volume removal is performed.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import time
from urllib.parse import urlencode
import uuid

from load import request, run as load_run, SessionClient
from functools import partial

ROOT = Path(__file__).resolve().parents[2]
METRICS = [
    "signalforge_events_ingested_total", "signalforge_events_processed_total",
    "signalforge_events_duplicates_total", "signalforge_incidents_created_total",
    "signalforge_ingestion_rate_limit_rejections_total", "signalforge_processing_failures_total",
    "signalforge_incidents_total",  # Actual exporter name: _created is a reserved suffix.
]
API = "http://localhost:8080"


def compose(*args, stdin=None, timeout=90):
    result = subprocess.run(["docker", "compose", *args], cwd=ROOT, input=stdin,
                            text=True, encoding="utf-8", capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Compose {args[:2]} failed: {result.stderr[-1000:]}")
    return result.stdout.strip()


def sql(query):
    raw = compose("exec", "-T", "postgres", "psql", "-U", "signalforge", "-d", "signalforge",
                  "-At", "-v", "ON_ERROR_STOP=1", "-c", query)
    return json.loads(raw)


def database():
    return sql("""SELECT json_build_object(
      'events', (SELECT count(*) FROM events),
      'processed_events', (SELECT count(*) FROM processed_events),
      'incidents', (SELECT count(*) FROM incidents),
      'event_ids', (SELECT coalesce(json_agg(id ORDER BY id),'[]') FROM events),
      'processed_ids', (SELECT coalesce(json_agg(event_id ORDER BY event_id),'[]') FROM processed_events),
      'incident_ids', (SELECT coalesce(json_agg(id ORDER BY id),'[]') FROM incidents),
      'migrations', (SELECT json_agg(row_to_json(m)) FROM
          (SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank) m))""")


def counts(snapshot):
    return {key: snapshot[key] for key in ("events", "processed_events", "incidents")}


def scoped(service):
    if not re.fullmatch(r"[A-Za-z0-9_-]+", service):
        raise ValueError("Use alphanumeric service names for this local SQL probe")
    return sql(f"""SELECT json_build_object(
      'events', (SELECT count(*) FROM events WHERE service='{service}'),
      'processed_events', (SELECT count(*) FROM processed_events p JOIN events e ON e.id=p.event_id WHERE e.service='{service}'),
      'incidents', (SELECT count(*) FROM incidents WHERE service='{service}'))""")


def catch_up(service, expected, timeout=60):
    started = time.perf_counter()
    first = scoped(service)
    current = first
    while current["processed_events"] < expected and time.perf_counter() - started < timeout:
        time.sleep(0.5)
        current = scoped(service)
    if current["events"] != expected or current["processed_events"] != expected:
        raise AssertionError(f"Processing reconciliation failed: {current}, expected {expected}")
    return {"first_observation": first, "final": current,
            "observed_catch_up_seconds": time.perf_counter() - started}


def metrics():
    values = {}
    expressions = {name: f"sum({name}) or vector(0)" for name in METRICS}
    expressions.update({
        "process_start_time_seconds": "max(process_start_time_seconds{job=\"signalforge\"})",
        "heap_bytes": "sum(jvm_memory_used_bytes{job=\"signalforge\",area=\"heap\"})",
        "process_cpu_usage": "max(process_cpu_usage{job=\"signalforge\"})",
        "pool_max": "max(hikaricp_connections_max{job=\"signalforge\"})",
    })
    for name, expression in expressions.items():
        result = request("http://localhost:9090/api/v1/query?" + urlencode({"query": expression}), timeout=10)
        if result["status"] != 200:
            raise RuntimeError("Prometheus query failed")
        rows = result["response"]["data"]["result"]
        values[name] = float(rows[0]["value"][1]) if rows else None
    return values


def after_scrape():
    # Prometheus scrapes every 15s. Avoid mistaking a previous scrape for a new observation.
    time.sleep(17)
    return metrics()


def metric_delta(before, after):
    if before["process_start_time_seconds"] != after["process_start_time_seconds"]:
        return {"counter_delta_not_comparable": "backend process changed"}
    return {name: after[name] - before[name] for name in METRICS}


def offsets():
    text = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-get-offsets.sh",
                   "--bootstrap-server", "kafka:29092", "--topic", "signalforge.events")
    return sum(int(match.group(1)) for line in text.splitlines()
               if (match := re.fullmatch(r"signalforge\.events:\d+:(\d+)", line)))


def duplicate(message, copies=3):
    record = message["id"] + "\t" + json.dumps(message) + "\n"
    compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh",
            "--bootstrap-server", "kafka:29092", "--topic", "signalforge.events",
            "--property", "parse.key=true", stdin=record * copies)
    return {"event_id": message["id"], "copies": copies, "method": "console producer, original UUID key and JSON value"}


def wait_healthy(service, timeout=180):
    start = time.perf_counter()
    while time.perf_counter() - start < timeout:
        container = compose("ps", "-q", service)
        if container:
            state = subprocess.check_output(
                ["docker", "inspect", "--format", "{{.State.Health.Status}}", container],
                text=True, timeout=10).strip()
            if state == "healthy":
                return round(time.perf_counter() - start, 3)
        time.sleep(2)
    raise TimeoutError(f"{service} did not become healthy in {timeout}s")


def health_summary():
    result = compose("ps", "--format", "json")
    # Modern Compose emits one JSON object per line.
    return [{"service": row["Service"], "state": row["State"], "health": row.get("Health", "")}
            for row in map(json.loads, result.splitlines())]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--include-failures", action="store_true",
                        help="Explicitly authorize temporary backend/Kafka/PostgreSQL interruptions")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        client = SessionClient.from_environment(API)
    except ValueError as error:
        parser.error(str(error))
    run = partial(load_run, request_fn=client.request)
    prefix = "load-validation-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6]
    evidence = {"run": prefix, "started_utc": datetime.now(timezone.utc).isoformat(), "scenarios": {}}
    stopped = set()

    def save():
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")

    def record(name, value):
        evidence["scenarios"][name] = value
        save()
        print(name + ": " + json.dumps(value), flush=True)

    try:
        baseline = database()
        evidence["baseline"] = counts(baseline)
        evidence["initial_health"] = health_summary()
        evidence["initial_metrics"] = metrics()
        save()
        print("Waiting 61s for a naturally fresh single-client rate-limit window.", flush=True)
        time.sleep(61)
        service = prefix + "-burst"
        before = metrics()
        offset_before = offsets()
        burst = run(total=20, concurrency=4, service=service)
        reconciliation = catch_up(service, burst["accepted_201"])
        offset_after = offsets()
        after = after_scrape()
        record("burst", {**burst, "reconciliation": reconciliation,
                         "kafka_offset_delta": offset_after - offset_before,
                         "metrics_before": before, "metrics_after": after, "metric_delta": metric_delta(before, after)})
        assert burst["accepted_201"] == 10 and burst["rejected_429"] == 10
        assert offset_after - offset_before == 10
        assert reconciliation["final"]["incidents"] == 1
        accepted = [row["response"] for row in burst["results"] if row["status"] == 201]
        actual_ids = sql(f"SELECT json_agg(id) FROM events WHERE service='{service}'")
        assert set(actual_ids) == {event["id"] for event in accepted}
        for event in accepted:
            marker_count = sql(f"SELECT count(*) FROM processed_events WHERE event_id='{event['id']}'")
            assert marker_count == 1

        before = metrics()
        replay = duplicate(accepted[0])
        after = after_scrape()
        replay.update({"counts": scoped(service), "metric_delta": metric_delta(before, after)})
        record("duplicate", replay)
        assert replay["counts"] == reconciliation["final"]
        assert replay["metric_delta"]["signalforge_events_duplicates_total"] == 3

        if args.include_failures:
            print("Waiting 61s before restart-state experiment; no limiter bypass.", flush=True)
            time.sleep(61)
            restart_service = prefix + "-restart"
            fixed_timestamp = datetime.now(timezone.utc).isoformat()
            first_two = run(2, 1, restart_service, timestamp=fixed_timestamp)
            assert first_two["accepted_201"] == 2
            pre = catch_up(restart_service, 2)
            assert pre["final"]["incidents"] == 0
            before_restart = database()
            metrics_before_restart = after_scrape()
            print("Restarting backend only.", flush=True)
            restart_start = time.perf_counter()
            compose("restart", "backend")
            wait_healthy("backend")
            client.login()  # Process-local sessions are invalid after a backend restart.
            recovery = time.perf_counter() - restart_start
            metrics_after_restart = after_scrape()
            survived = database()
            assert counts(before_restart) == counts(survived)
            assert before_restart["migrations"] == survived["migrations"]
            post_one = run(1, 1, restart_service, timestamp=fixed_timestamp)
            assert post_one["accepted_201"] == 1
            after_one = catch_up(restart_service, 3)
            assert after_one["final"]["incidents"] == 0
            post_two = run(2, 1, restart_service, timestamp=fixed_timestamp)
            assert post_two["accepted_201"] == 2
            after_three = catch_up(restart_service, 5)
            assert after_three["final"]["incidents"] == 1
            record("restart", {"service": restart_service, "fixed_event_timestamp": fixed_timestamp,
                              "pre_restart_two": pre, "post_restart_one": after_one,
                              "post_restart_three": after_three, "recovery_seconds": recovery,
                              "counts_before": counts(before_restart), "counts_after": counts(survived),
                              "metrics_before": metrics_before_restart, "metrics_after": metrics_after_restart})
            before = after_scrape()
            replay = duplicate(accepted[0])
            after = after_scrape()
            replay.update({"counts": scoped(service), "metric_delta": metric_delta(before, after)})
            record("duplicate_after_restart", replay)
            assert replay["counts"]["incidents"] == 1
            assert replay["metric_delta"]["signalforge_events_duplicates_total"] == 3

            kafka_service = prefix + "-kafka-outage"
            before = metrics()
            kafka_db_before = database()
            print("Stopping Kafka; one POST will wait for the unmodified producer timeout.", flush=True)
            stopped.add("kafka")
            compose("stop", "kafka")
            try:
                failed = run(1, 1, kafka_service, timeout=200)
                during = scoped(kafka_service)
                record("kafka_outage", {"request": failed, "database": during,
                                        "metrics_before": before, "metrics_after": after_scrape()})
            finally:
                compose("start", "kafka")
                stopped.discard("kafka")
            kafka_recovery_start = time.perf_counter()
            wait_healthy("kafka")
            recovered = run(1, 1, prefix + "-kafka-recovery")
            assert recovered["accepted_201"] == 1
            catchup = catch_up(recovered["service"], 1)
            record("kafka_recovery", {"recovery_seconds": time.perf_counter() - kafka_recovery_start,
                                     "request": recovered, "reconciliation": catchup,
                                     "outage_rows_after_recovery": scoped(kafka_service),
                                     "counts_before_outage": counts(kafka_db_before),
                                     "counts_after_recovery": counts(database()), "metrics": after_scrape()})

            print("Stopping PostgreSQL; checking health, GET and POST concurrently.", flush=True)
            pg_before = database()
            pg_metrics_before = metrics()
            pg_service = prefix + "-postgres-outage"
            stopped.add("postgres")
            compose("stop", "postgres")
            try:
                with ThreadPoolExecutor(max_workers=3) as pool:
                    health = pool.submit(request, API + "/actuator/health", timeout=50)
                    get = pool.submit(client.request, API + "/events", timeout=50)
                    post = pool.submit(run, 1, 1, pg_service, timeout=50)
                    record("postgres_outage", {"health": health.result(), "get": get.result(), "post": post.result(),
                                               "metrics_before": pg_metrics_before, "metrics_after": after_scrape()})
            finally:
                compose("start", "postgres")
                stopped.discard("postgres")
            pg_recovery_start = time.perf_counter()
            wait_healthy("postgres")
            wait_healthy("backend")
            pg_survived = database()
            assert counts(pg_before) == counts(pg_survived)
            recovered = run(1, 1, prefix + "-postgres-recovery")
            assert recovered["accepted_201"] == 1
            catchup = catch_up(recovered["service"], 1)
            record("postgres_recovery", {"recovery_seconds": time.perf_counter() - pg_recovery_start,
                                        "request": recovered, "reconciliation": catchup,
                                        "counts_before": counts(pg_before), "counts_after": counts(pg_survived),
                                        "failed_request_rows": scoped(pg_service), "metrics": after_scrape()})

        final = database()
        evidence["final"] = counts(final)
        evidence["added"] = {key: final[key] - baseline[key] for key in counts(final)}
        evidence["integrity"] = {
            "original_events_survived": set(baseline["event_ids"]).issubset(final["event_ids"]),
            "original_incidents_survived": set(baseline["incident_ids"]).issubset(final["incident_ids"]),
            "original_markers_survived": set(baseline["processed_ids"]).issubset(final["processed_ids"]),
            "flyway_history_unchanged": baseline["migrations"] == final["migrations"],
        }
        assert all(evidence["integrity"].values())
        evidence["final_health"] = health_summary()
        evidence["final_metrics"] = after_scrape()
        evidence["complete"] = True
        save()
        print("COMPLETE " + json.dumps({"final": evidence["final"], "added": evidence["added"]}), flush=True)
    except BaseException as error:
        evidence["error"] = type(error).__name__ + ": " + str(error)
        save()
        raise
    finally:
        # Best-effort restoration even on assertions/errors/Ctrl+C; never remove/recreate data.
        for service in ("postgres", "kafka"):
            if service in stopped:
                print("Restoring " + service, flush=True)
                compose("start", service)
                wait_healthy(service)


if __name__ == "__main__":
    main()