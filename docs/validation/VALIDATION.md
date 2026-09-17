# SignalForge: load, failure, and scaling validation

Measured on 2026-09-17. Run: **load-validation-20260917T043823Z-3e27a8**.
Raw request results, UUIDs, database reconciliation, Prometheus snapshots, and integrity checks: [evidence.json](evidence.json).
Tooling: [load.py](../../scripts/validation/load.py), [validate.py](../../scripts/validation/validate.py), and [utility tests](../../scripts/validation/test_load.py).

**Scope:** controlled local engineering checks, not a statistically rigorous benchmark, production capacity test, HA test, or exactly-once proof. The limiter, durability, synchronous Kafka acknowledgement, detection rules, and database schema were not weakened. No data or volumes were deleted.

## 1. Environment and methodology — MEASURED

Windows host; Linux containers on Docker Desktop. Host: 4 logical processors and approximately 11.9 GiB RAM; Docker reported 4 CPUs and approximately 5.75 GiB memory. Python 3.11.3 standard library, Java 21, Node 24.21.0. Spring Boot 4.1.1, PostgreSQL 16, Kafka broker 3.8.1, and the existing Nginx/Prometheus/Grafana services. The application's Kafka client reported 4.2.1; broker and client versions are different components.

One client sent direct POST /events requests to localhost:8080. The tool uses ThreadPoolExecutor, urllib, and perf_counter; latencies include client/HTTP/server time but exclude executor queue time. Percentiles linearly interpolate sorted observations. All HTTP statuses and transport errors count toward attempted requests. Results include a separate accepted-only latency distribution. No automatic POST retries occur.

A 61-second quiet period allowed the fixed window to expire naturally. Unique service suffixes isolate each scenario; all use LOAD_TEST/HIGH. Run alone: concurrent ingestion makes global counter/offset deltas ambiguous. Counter snapshots wait 17 seconds for the configured 15-second Prometheus scrape. Missing counter series normalize to zero; that does not prove the metric exists (see the incident metric defect below).

## 2. Baseline and data integrity — MEASURED

| Table | Before | Added | After |
|---|---:|---:|---:|
| events | 33 | 17 | 50 |
| processed_events | 23 | 17 | 40 |
| incidents | 7 | 2 | 9 |

The initial stack had four healthy services (frontend/backend/PostgreSQL/Kafka) plus running Prometheus/Grafana. Ten historical events already lacked processed markers before this milestone; their origin was not inferred or repaired. There were no markers without corresponding events. Every accepted event from this run has exactly one marker.

All original event, incident, and marker UUIDs survived. Flyway history/checksums remained V1–V5; backend restart logs validated five migrations with no migration needed. Per-service additions:

| Service suffix under the run prefix | Events | Markers | Incidents |
|---|---:|---:|---:|
| -burst | 10 | 10 | 1 |
| -restart | 5 | 5 | 1 |
| -kafka-outage | 0 | 0 | 0 |
| -kafka-recovery | 1 | 1 | 0 |
| -postgres-outage | 0 | 0 | 0 |
| -postgres-recovery | 1 | 1 | 0 |

Validation rows remain intentionally present. Duplicate Kafka deliveries add no event rows.

## 3. Rate-limit and accepted-traffic test — MEASURED

Concurrency: **4**; attempted: **20**; HTTP 201: **10**; HTTP 429: **10**; transport failures: **0**. Elapsed: **0.476s**.

| Latency, all 20 responses | Milliseconds |
|---|---:|
| Minimum | 29.34 |
| Average | 91.93 |
| p50 | 104.95 |
| p95 | 139.78 |
| p99 | 141.12 |
| Maximum | 141.46 |

Accepted-only mean: 119.74 ms; accepted-only p95: 140.66 ms. Small-sample p95/p99 are descriptive, not stable tail estimates.

The computed accepted/elapsed rate is 21.01/s **for this subsecond burst only**. It is not sustained capacity: one client is intentionally limited to ten allowed POST attempts per 60-second window. Failed allowed POSTs also consume quota. Concurrent requests have no promised index-based arrival order; exactly ten passed the synchronized limiter.

The rejection counter increased by 10. The persisted UUID set exactly matched the ten HTTP 201 response UUIDs; all ten had processed markers. The topic end-offset sum increased by exactly ten in this otherwise quiet run. Together these checks show the rejected requests added no persisted, published, or processed events.

## 4. Processing catch-up and detector behavior — MEASURED

The first database observation after the burst already found 10 persisted and 10 processed events (gap zero), plus one incident. The probe took 0.657s, including Docker/psql execution; this is **not** per-event processing latency. HTTP 201 was never treated as proof of asynchronous completion. The runner allows 60 seconds for catch-up if needed.

This is application-level processing reconciliation, not a measured Kafka lag percentile. Kafka also exposes client lag series, but they were not used to invent a broker-lag claim.

Ten matching events within a short event-time interval created one incident; the cooldown suppressed repeated incidents. Detection uses the supplied event timestamp, a 60-second inclusive window, threshold three, and 60-second event-time cooldown. Lower-severity events also count. Full late/out-of-order reconstruction is not implemented.

## 5. Durable idempotency — MEASURED

The Kafka console producer resent an accepted event's **same UUID key and complete EventMessage JSON** three times. This was not three repeated HTTP payloads (which would generate new IDs).

Before restart: duplicate metric +3, processed markers unchanged, incident count unchanged.
After restart: the same three-message replay again produced duplicate metric +3 and no new marker/incident. The latter check ran with cleared detector/cooldown memory, strengthening the check that duplicates did not re-enter detection. Across both epochs, six deliberate duplicate deliveries added no database rows. The exact event ID is in evidence.json.

The PostgreSQL primary key plus INSERT ... ON CONFLICT DO NOTHING is the durable deduplication boundary. Existing integration tests additionally verify that duplicate delivery skips detection and processing failure rolls back marker registration.

## 6. Backend restart — MEASURED

A backend-only Docker restart returned to Docker health in approximately **43.10s**, measured from the restart command through observed healthy status. This is one local recovery observation, not an availability/SLA guarantee.

Before/after restart counts were {'events': 45, 'processed_events': 35, 'incidents': 8}; no rows were lost. API and consumer resumed, and Prometheus scraped the new process.

For deterministic state-loss validation, two events were processed before restart and one after, with **identical event timestamps**. No incident appeared after that third durable event. Two more post-restart events then created one incident. Holding event time constant rules out natural window expiry as the explanation.

The process start timestamp changed; ingestion/processing counters changed from 15 to absent/zero, duplicates 3 to absent/zero, and rejection counter 10 to zero. These are separate process epochs, not negative deltas. Source inspection also confirms rate-limit windows reset on restart; no separate exhausted-quota experiment was performed.

## 7. Kafka interruption/recovery — MEASURED

Kafka was stopped without deleting/recreating its container storage. One POST returned **HTTP 500** after **60.10s**. It left **0 events and 0 markers** for its unique service. The backend log specifically reported metadata unavailable after 60000 ms; this run hit max.block.ms, not the longer delivery timeout. The unmodified producer configuration reported max.block.ms=60000, delivery.timeout.ms=120000, and acks=-1.

Kafka was started again. Healthy-broker observation plus successful new ingestion and processing took approximately **26.59s** after the start command returned. One new recovery event was persisted and processed. Its first database observation showed one event but zero markers; the marker appeared within the 2.19-second catch-up probe. The failed-outage service still had zero rows after recovery; nothing was manually repaired. Consumer reconnect logs and successful marker creation confirm recovery.

### Dual-write interpretation — SOURCE-BASED ANALYSIS

EventService is transactional: saveAndFlush executes the SQL INSERT, then publish waits for acknowledgement, and only successful method return commits PostgreSQL. The observed Kafka failure threw and rolled back the INSERT. It would be incorrect to describe this experiment as a persisted-but-unpublished row.

This still is not atomic DB/Kafka publication. Kafka can accept a message before the DB transaction commits; a later DB commit failure or uncertain producer outcome can leave Kafka work without a committed event. The consumer need not wait for that source-row commit, and marker/incident tables do not have source-event foreign keys. This failure window was identified in source, not forced experimentally. Transactional Outbox is future work, not implemented.

## 8. PostgreSQL interruption/recovery — MEASURED

PostgreSQL was stopped with its volume retained. Concurrent probes observed:

| Probe | HTTP status | Seconds |
|---|---:|---:|
| /actuator/health | 503 | 30.04 |
| GET /events | 500 | 30.05 |
| POST /events | 500 | 30.05 |

The backend process remained running; connection-pool/database errors appeared rather than a process exit. After starting PostgreSQL, existing counts matched the pre-outage snapshot. Backend health recovered and a new event was persisted/processed in approximately **13.16s** after the start command returned. The failed POST left no service-scoped rows.

Timing includes pool/healthcheck polling and is not a recovery-time objective. No WAL corruption, table deletion, truncation, volume removal, or database recreation was attempted.

## 9. Retry/DLT evidence — AUTOMATED + SOURCE INSPECTION

KafkaConsumerConfig specifies FixedBackOff(1000ms, 2 retries): **three total attempts for retryable listener failures**, then signalforge.events.dlt, always partition 0. The DLT has one partition and replication factor one. Original key/value are preserved. Exception-class changes do not reset the retry budget.

setFailIfSendResultIsError(true) makes failed DLT publication throw rather than count as successful recovery. The handler's default recovery-failure behavior allows redelivery and resets backoff; therefore three attempts is not a universal lifetime bound when DLT recovery itself fails. Framework-classified fatal exceptions can bypass retries. Arbitrary malformed JSON/deserialization recovery was not proven.

Five existing KafkaConsumerConfigTests cover retry budget/backoff, third-failure recovery, successful retry, DLT destination/key/value, and failed DLT send. These use mocks, not a live broker DLT test. No artificial poison event, production failure hook, or destructive live DLT injection was added.

For container offset and error-handler defaults, see [Spring Kafka listener containers](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html) and [exception handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html).

## 10. Prometheus observations and observability correction

**Measured:** ingestion and processing +10 during the burst, rejection +10, duplicates +3 in each of two separate epochs. No listener-processing failure increment was observed in these drained-outage scenarios; producer/HTTP/database availability failures do not automatically increment the listener failure counter. Missing series are not evidence that an operation never failed.

Observed snapshot heap ranged **40.20–92.91 MiB**; process_cpu_usage ranged **0.0022–0.5000** as exported fractions. These include startup/outage snapshots and scrape averaging, not peak memory, saturation measurements, or CPU percentages attributable to individual requests. Hikari reported a maximum pool size of ten.

**Observed bug:** PostgreSQL contained a newly created incident, but the dashboard's signalforge_incidents_created_total query returned no series (its zero fallback hid that). The actual export is signalforge_incidents_total; the Prometheus exporter normalizes the reserved _created suffix. The correct raw series counted the incident.

**Small fix:** changed only the two existing Grafana incident-panel expressions to signalforge_incidents_total. No product/runtime business logic changed. Added a backend regression test that extracts incident metric names from the actual provisioned dashboard, checks each against the real Prometheus scrape, and verifies the service-specific counter value. Provisioned Grafana was reloaded, and the corrected query was rechecked against Prometheus. The saved original run retains the missing-name zero observations; it has not been rewritten to pretend they were correct. Future runner snapshots include both the requested name and actual exported name.

Counters are process-local. Success counters are scheduled after transaction commit; duplicate and failed-attempt counters have different semantics. Tags cap distinct values at 20 per dimension and then use other, limiting cardinality but reducing per-service detail.

## 11. Query/index observations — MEASURED

Read-only EXPLAIN (not EXPLAIN ANALYZE) was run on two LIMIT 20 queries: events filtered by service ordered by timestamp/id descending, and incidents filtered by service/status ordered by created_at/id descending. Both chose sequential scans followed by sorting on this tiny dataset. That is a legitimate small-table plan, not proof the existing composite indexes are broken. No index, planner, schema, or statistics tuning was performed.

## 12. Horizontal scaling — ARCHITECTURAL ANALYSIS, NOT A MULTI-NODE TEST

| Concern | Current consequence / future requirement |
|---|---|
| Consumer group / partitions | One observed event partition allows only one active consumer for that partition in the group. Extra replicas do not provide parallel processing for this topic. |
| Partition key | Publisher keys by random event UUID, not service+type. Increasing partitions would scatter related detector inputs across consumers. |
| Detector correctness | Window/cooldown maps are local, synchronized only within one JVM, and lost on restart/rebalance. This is the main horizontal correctness blocker. Stable service+type partitioning plus durable/restorable state or shared transactional windows is needed; partitioning alone does not survive restart. |
| Global rate limiting | Ten requests per remote address per JVM is not a cluster-wide limit. Replicas can each grant a budget. The limiter is not proxy-aware; Nginx traffic can share its apparent client address. Future trusted gateway/shared enforcement is required. |
| Durable idempotency | Shared PostgreSQL UUID uniqueness prevents repeated registration across replicas, with contention for the same ID. It does not prevent distinct events from producing duplicate incidents through fragmented detector state. |
| Optimistic locking | Incident version checks protect concurrent updates to the same incident row; HTTP 409 is supported. They do not coordinate creation of separate incident rows or detector windows. |
| Database | Event writes, marker writes, incident writes and paginated/count reads share one primary. Slow synchronous Kafka acknowledgement can hold a DB transaction/connection. Replica pools add connections/contention; index write costs and offset/count-query costs grow. No database throughput ceiling was measured. |
| Metrics | Scrape each replica with distinct identity and aggregate rates/increases correctly across restarts. Process-local absolute counters cannot be treated as durable business totals. |
| Frontend / routing | Fixed host port 8080 blocks naive Compose scaling. Service discovery, load balancing, readiness, and Nginx upstream re-resolution need deliberate design. |
| Local state growth | Inactive detector keys/IP limiter keys are not globally evicted. Active windows prune by event time; idle-key retention is a potential long-lived memory concern, not a measured leak here. |
| Transactional state | Marker and incident DB writes share a transaction, but detector maps are not transactionally rolled back. Failed/retried processing and late events need stronger state consistency before production claims. |

One broker and replication factor one are **not high availability**. Listener retries are application error handling, not broker redundancy. Existing Kafka container-local storage limitations remain. No second backend instance was started and no partition count or broker offsets were changed.

The delivery model supports at-least-once consumer delivery with durable application-level duplicate registration for valid messages, subject to broker retention, offset/error recovery, and DB availability. Batch container offset commits are separate from the application DB transaction. It does not provide exactly-once end-to-end ingestion or durable detector-state transactions.

## 13. Bottlenecks, justified claims, and future work

**Justified:** the configured single-client limiter admitted ten requests; accepted events in this run were persisted and processed; duplicate UUID deliveries were skipped across restart; controlled local dependency outages recovered without deleting data.

**Not justified:** a sustained 21 requests/second capacity claim, high-volume scaling, latency SLOs, multi-instance detector correctness, broker HA, disaster recovery, or exactly-once processing. There was only one small burst, on a shared development machine, with scrape/probe overhead and a hard quota.

Future improvements (not implemented): transactional outbox; service+type partitioning with durable/restorable stream state (for example Kafka Streams) or correctly synchronized shared/database windows; trusted proxy identity and distributed rate limiting; replicated durable Kafka storage; measured pool/query tuning and bounded state retention; load balancing and per-instance metric aggregation. Read replicas might help sufficiently large read workloads, while sharding would add substantial complexity and is not justified by this dataset.

## 14. Reproduction

Prerequisites: the normal healthy six-service stack, Python 3.11+ with no third-party packages, Docker CLI, and repository-root working directory. Use a disposable/local development environment and avoid other traffic.

Utility checks and load generation:

    python -B -m unittest discover -s scripts/validation -p "test_*.py" -v
    python -B scripts/validation/load.py --requests 20 --concurrency 4 --service load-validation-UNIQUE --type LOAD_TEST --severity HIGH --output backend/target/load-result.json

The generator additionally supports --base-url and --timeout. Its default service is unique. Wait at least 61 quiet seconds before a fresh-window burst; larger accepted-volume measurements require paced windows, not disabling the limiter.

Safe load/idempotency evidence (no stop/start):

    python -B scripts/validation/validate.py --output backend/target/safe-validation.json

Explicit outage/restart experiments, including best-effort finally restoration:

    python -B scripts/validation/validate.py --include-failures --output backend/target/failure-validation.json

This adds 17 events/17 markers/two incidents if the full scenario succeeds against the same semantics; baseline totals on another run need not match this report. Do not overwrite the checked-in sample evidence. The failure run takes several minutes, including natural windows, scrape waits, producer timeout and health polling. Console duplicate injection replays only a real accepted ID. No cleanup deletes validation rows.

If execution is forcibly killed, restore dependencies with docker compose start postgres kafka backend, then inspect docker compose ps and backend health. Never use down -v or prune. On assertion failure, inspect the output JSON before rerunning; previous successful requests remain data.

Automated application verification (after dependencies are healthy):

    cd backend
    .\mvnw.cmd "-Duser.timezone=Asia/Kolkata" verify
    cd ../frontend
    npm run test
    npm run build
    cd ..
    docker compose config

## 15. Final verification and remaining manual work

The completed checks are recorded in evidence.json under post_validation_checks. Maven: **81 tests** (80 existing plus exported-name regression); frontend: **33 tests**; utility: **5 tests**; frontend build and Compose configuration passed. Four healthchecked services are healthy, Prometheus/Grafana are running, backend health is UP, the frontend serves HTTP 200, Prometheus scrapes backend:8080 as UP, and Grafana's datasource/dashboard queries work.

No functional validation remains for this milestone. Optional manual work: visually inspect the existing Grafana incident panels over the validation time range. This report verifies their query data through APIs, not browser pixels. Live DLT poisoning, multi-instance deployment, large-volume testing, and visual UI redesign were intentionally not performed.
