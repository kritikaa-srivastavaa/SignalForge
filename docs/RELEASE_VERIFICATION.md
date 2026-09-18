# Prompt 27 release verification

Verified 18 September 2026 on branch `feature/event-ingestion`.

## Release status

V1 is ready for repository review and a local portfolio demo. Documentation/configuration polish is complete; authenticated screenshot capture and real SMTP delivery remain manual. Neither is represented as verified. No genuine code/build/stack release blocker was found. Public production deployment requires the hardening documented in SECURITY.md.

## Verification

| Check | Result |
| --- | --- |
| Maven verify, Java 21 / Asia/Kolkata | 199 tests, zero failures/errors/skips; BUILD SUCCESS |
| Frontend Vitest | 96 tests, six files; passed |
| Validation unit tests | 10 passed |
| TypeScript / Vite production build | Passed |
| docker compose config --quiet | Passed |
| Six-service stack | All running; backend/frontend/PostgreSQL/Kafka healthy |
| Actuator health | UP |
| Prometheus signalforge target | up |
| Grafana datasource | OK |
| Provisioned dashboard expressions | All 10 query successfully |
| git diff --check | Passed |

Existing stack/images were already running the verified V1 baseline. Documentation-only changes did not require a service rebuild or restart. No failure/load experiments were run. Authenticated backend integration tests use existing test setup/isolated fixtures; no personal credentials were requested.

## Files

Updated: README.md, .gitignore, .env.example.
Created: docs/ARCHITECTURE.md, docs/API.md, docs/DEVELOPMENT.md, docs/SECURITY.md, docs/DEMO.md, docs/RELEASE_VERIFICATION.md, docs/screenshots/README.md.

README covers product behavior, accurate single-backend architecture, implemented engineering features, final access model, tech stack, quick start/ports, API overview, testing, project structure, V1 limitations, screenshots, and demo links. Historical reports/evidence/assets and all migrations remain intact.

## Cleanup and security

No accidental version-controlled generated files required deletion. Existing ignores cover Maven/Node/IDE/log/temp/environment artifacts; Python cache patterns were added. backend/run.log, target logs, node_modules, dist, and local .env are ignored. No application source/configuration/migration was changed. Safe root .env.example retains optional bootstrap and adds SMTP placeholders without real values.

A bounded pattern/file-size scan covered 187 tracked/new files before this report: no obvious private keys, recognizable GitHub/AWS tokens, literal SMTP passwords, generated files, or files over 5 MB were found. Public development database/Grafana defaults and intentional synthetic test passwords are not personal secrets; they are documented as local-only. This is not a comprehensive secret-auditing guarantee. No staged changes were present. Final changes are three modified root files and seven new documentation files.

## Data preservation

Read-only comparison against the preserved post-Prompt-26.2 snapshot confirmed every original nonsecret row unchanged: 9 accounts, 62 events, 13 incidents, 52 processing markers, 4 access requests, 15 audits, and 9 Flyway records. Passwords were neither read into the comparison nor modified. Existing ADMIN ownership remains unchanged. No Docker cleanup, database reset, record deletion, or migration rewrite occurred.

## Manual verification and screenshots

Use only an authorized existing session; do not reset accounts or request personal credentials for checks. Authenticated live browser workflows/visual QA were not repeated because reusable synthetic account passwords are not retained. Automated frontend/backend checks passed, but do not replace live browser verification. SMTP is disabled locally; provider configuration and actual inbox delivery remain manual.

Capture real screenshots at:

- http://localhost:5173/ ? Overview
- http://localhost:5173/events ? Events
- http://localhost:5173/incidents/{actual-uuid} ? Incident detail as OPERATOR/ADMIN
- http://localhost:5173/access/review ? group governance; optionally /admin/access-requests as ADMIN
- http://localhost:3000/d/signalforge-overview ? Grafana dashboard

Save reviewed images under docs/screenshots/ using its checklist; redact personal identifiers. No screenshots were fabricated or nonexistent images linked.

## Confirmations

No new features. No architecture redesign. Existing data preserved. No destructive Docker operations. Nothing staged, committed, or pushed by this task. Existing commit history was not rewritten.

Routine logs/data/security outputs are local ignored files under backend/target/release-*; this report is the version-controlled summary.
