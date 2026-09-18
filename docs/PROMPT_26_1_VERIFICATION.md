# Prompt 26.1 verification

Completed on 18 September 2026 on `feature/event-ingestion`. This report covers only the email and favicon additions; the earlier Prompt 26 redesign remains uncommitted in the working tree.

## Email

Added Spring Boot-managed `spring-boot-starter-mail`, with no manually pinned version. `AccessRequestService` publishes immutable request ID/email/time data after saving and recording the existing audit entry. `AccessRequestNotificationService` listens AFTER_COMMIT, so rollback or failed creation produces no email. It reads current ADMIN email addresses in a fresh read-only transaction and sends separate concise plain-text messages. OPERATOR and VIEWER users are not recipients. Passwords, cookies, session IDs, CSRF tokens, and unnecessary user fields are excluded.

Mail is disabled by default. Delivery/recipient lookup failures are contained; notification logs omit provider messages and stack traces. One recipient's failure does not stop other recipients. Zero administrators produces a safe warning. A committed request stays PENDING and visible in the ADMIN queue. Missing MAIL_FROM skips delivery safely. SMTP probing does not participate in application health. Delivery is synchronous, best effort, with 3-second SMTP connection/read/write timeouts; there is no durable delivery guarantee or retry mechanism.

Configuration: SIGNALFORGE_MAIL_ENABLED, MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM, MAIL_SMTP_AUTH, MAIL_STARTTLS_ENABLED, and SIGNALFORGE_PUBLIC_URL. Defaults, provider setup, and failure behavior are documented in [ACCESS_REQUEST_EMAIL.md](ACCESS_REQUEST_EMAIL.md). Compose passes settings into the existing backend; no mail service or other infrastructure was added. No credentials are committed.

Added 13 tests: 7 PostgreSQL-backed integration tests and 6 unit tests. They cover all-admin selection/exclusion of other roles, separate message content, post-commit timing, rollback suppression, mail failure leaving a request in the queue, zero admins, duplicate/privileged creation failures, rejected-request resubmission, disabled mail, missing sender, lookup failure, and continued delivery after one failure. All automated deliveries use a mocked JavaMailSender.

**Manual:** actual SMTP provider delivery requires authorized environment configuration and a provider delivery check. No real email was sent by verification, and no personal credentials were created or requested.

## Favicon and title

`frontend/public/favicon.svg` is an original geometric white signal/pulse on an indigo (#4F46E5) rounded square. It matches the established SignalForge identity and uses no icon dependency. `frontend/index.html` references the SVG and updates theme-color to the existing accent. Existing SignalForge initial and page-aware console titles are preserved; no metadata framework or layout redesign was introduced.

Actual Microsoft Edge loaded the deployed Nginx frontend. The favicon returned HTTP 200 with image/svg+xml, decoded successfully, and rendered at 16x16. The live public browser title was SignalForge ? Operations. Verification did not require authentication. The browser was closed afterwards.

## Regression and production

| Check | Result |
| --- | --- |
| Java 21 Maven verify, Asia/Kolkata workaround | 178 passed; 0 failures/errors/skips; BUILD SUCCESS |
| Frontend tests | 85 passed across 6 files |
| TypeScript/Vite production build | Passed |
| Validation | 10 passed |
| Compose configuration | Passed |
| Production images | Backend and frontend rebuilt/restarted; no volume reset |
| Six services | Running; backend/frontend/PostgreSQL/Kafka healthy |
| Actuator | UP |
| Prometheus target | Up |
| Grafana datasource and dashboard queries | OK; 10 queries successful |
| Git diff whitespace check | Passed |

An existing frontend test's first async render repeatedly exceeded its 1-second wait on this Windows/Docker machine. Only that wait was increased to 5 seconds; all behavior assertions remain unchanged. The final complete suite passed. Kafka health-check CLI timeouts during heavy build load resolved once load settled; no Kafka configuration was changed.

All original record IDs remain present. Final counts are 9 users, 62 events, 13 incidents, 52 processed-event markers, 4 access requests, and 12 audit records, stable across deployment. Compared with the earlier Prompt 26 snapshot, one user/request/audit addition predates this implementation (16:41-16:42 local); those legitimate records were preserved. Flyway V1-V8 checksums/success flags are identical. Isolated test records were cleaned up without deleting application data.

## Files for this prompt

Created:

- backend/src/main/java/com/kritika/signalforge/auth/AccessRequestCreated.java
- backend/src/main/java/com/kritika/signalforge/auth/AccessRequestNotificationService.java
- backend/src/test/java/com/kritika/signalforge/auth/AccessRequestNotificationIntegrationTests.java
- backend/src/test/java/com/kritika/signalforge/auth/AccessRequestNotificationServiceTests.java
- frontend/public/favicon.svg
- docs/ACCESS_REQUEST_EMAIL.md
- docs/PROMPT_26_1_VERIFICATION.md

Modified:

- backend/pom.xml
- backend/src/main/java/com/kritika/signalforge/auth/AccessRequestService.java
- backend/src/main/java/com/kritika/signalforge/auth/AppUserRepository.java
- backend/src/main/resources/application.properties
- docker-compose.yml
- frontend/index.html
- frontend/src/test/Governance.test.tsx (async wait only)

No migration/schema change, RBAC change, approval/rejection change, audit-semantics change, unrelated feature, or new permanent Docker service. Nothing committed or pushed. Temporary browser/test evidence remains ignored under backend/target/.
