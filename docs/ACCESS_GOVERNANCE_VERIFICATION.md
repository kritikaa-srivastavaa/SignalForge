# Final V1 access governance verification

Completed 18 September 2026 on `feature/event-ingestion`. The Prompt 26 design system and Prompt 26.1 notification infrastructure are preserved.

## Access, requests, and approval

| State | Access | Server-derived request | May review |
| --- | --- | --- | --- |
| NO_ACCESS | Own account/access screen; no operational reads, ingestion, lifecycle, or admin APIs | VIEWER | None |
| VIEWER | Existing operational reads | OPERATOR | VIEWER requests |
| OPERATOR | Existing reads and lifecycle actions | None | OPERATOR requests |
| ADMIN | Existing operational and administrative permissions | None | Both request levels, including emergency intervention |

New registration defaults to NO_ACCESS. Existing users retain their roles through migration, except the separately authorized local admin handover. Request bodies do not control identity, target role, status, reviewer, or timestamps. The backend derives exactly one next level. ADMIN cannot be requested. There is no automatic first-member grant.

Approval is owned by the actual requested group, not a generic role hierarchy: VIEWER cannot approve OPERATOR requests; OPERATOR cannot approve VIEWER requests. ADMIN still sees every request/status through the existing admin console and can intervene even when members exist. Group members receive only their own group's pending queue at GET /access-requests/review and review through PATCH /access-requests/{id}/approve or /reject. Admin Users/Audit remain ADMIN-only.

The existing role-change lock, transaction boundaries, self-review ban, duplicate-pending constraint, and stale-review conflicts remain authoritative. Approval atomically grants eligible requested access, transitions the request, records reviewer/time, and writes audit facts. Rejection retains lower access and allows re-request. Already satisfied higher access is never downgraded. A requester demoted below eligibility cannot skip a level with stale approval. Sessions use the existing current-role filter; My Access refresh updates client role/navigation after approval.

Audit actor IDs identify the actual VIEWER, OPERATOR, or ADMIN reviewer. Existing role-change and request audit actions are retained; no separate audit semantics were introduced.

## Email

The existing AFTER_COMMIT listener now selects exact requested-role members. A populated VIEWER group receives VIEWER admission notifications; a populated OPERATOR group receives OPERATOR notifications. Neither case routinely emails ADMIN or the other operational group. Only zero-member groups fall back to ADMIN. The requester is excluded and duplicate addresses removed. Links point to the appropriate member/admin review page.

Mail remains disabled by default, best effort, and isolated from the request transaction and application health. Failures cannot undo a committed PENDING request. Provider details/secrets are omitted from notification failure logs. No new dependency, notification system, messaging service, or permanent container was added. Configuration remains documented in [ACCESS_REQUEST_EMAIL.md](ACCESS_REQUEST_EMAIL.md).

Explicit tests cover zero/populated VIEWER and OPERATOR routing, requester/duplicate exclusion, no routine ADMIN notification, and delivery failure leaving the request pending and visible.

## Local ADMIN handover

The user explicitly approved promoting kritikaa.srivastavaa@gmail.com and demoting the existing synthetic administrators to VIEWER. The approved changes ran atomically through the existing UserAdministrationService under its role lock and audit service:

- kritikaa.srivastavaa@gmail.com: VIEWER -> ADMIN
- governance-admin-3c260069@signalforge.local: ADMIN -> VIEWER
- rbac-admin-d4fc589c@signalforge.local: ADMIN -> VIEWER

The final database contains exactly one ADMIN, two OPERATORs, and six VIEWERs. No account was deleted or created for this handover. All password hashes were compared in memory and confirmed unchanged without logging them. Exactly three USER_ROLE_CHANGED audit entries were added, with the actual service actor. Promotion preceded demotion; last-admin protection is unchanged.

An ignored local .env configures the intended bootstrap email with startup bootstrap disabled and no bootstrap password set. The existing bootstrap service still refuses to claim an existing public registration or reset credentials. No production authorization logic is hardcoded around an email address, and multiple-admin support remains technically available. The temporary local maintenance context closed after execution. Reload the app to obtain current ADMIN navigation.

## Frontend and database

NO_ACCESS is redirected to a quiet Viewer access required screen using existing components/styles; operational navigation and data calls are absent. VIEWER retains Request Operator Access. VIEWER/OPERATOR members have an Access Requests navigation item leading to /access/review. The existing compact queue and review controls are reused, without status filters/full admin access for members. ADMIN retains Users, all Access Requests, and Audit Log. My Access uses exact navigation matching so the nested review route does not select both items.

New migration: V9__add_no_access_and_viewer_requests.sql. It extends the app_users role check with NO_ACCESS, changes the future database role default to NO_ACCESS, and permits VIEWER/OPERATOR requested roles. V1-V8 are untouched. V9 applied successfully to the configured PostgreSQL database; the full migration set also passed the isolated empty-schema check.

Before/after comparisons verify every original non-secret row field, not just counts. Only the three authorized user roles differ; original events, incidents, processed markers, access requests, and audit entries are identical. Old Flyway rows/checksums are identical. Password preservation was separately verified during the handover. Final counts: 9 users, 62 events, 13 incidents, 52 processed markers, 4 access requests, 15 audit records, and 9 successful migrations. Automated fixtures were removed by their existing isolated cleanup.

## Verification

| Check | Result |
| --- | --- |
| Java 21 Maven verify, Asia/Kolkata timezone | 199 passed; zero failures/errors/skips; BUILD SUCCESS |
| Frontend regression | 96 passed across 6 files |
| TypeScript/Vite production build | Passed |
| Validation | 10 passed |
| Docker Compose configuration | Passed |
| Backend/frontend production images | Rebuilt and deployed; frontend remains Nginx at localhost:5173 |
| Six-service stack | All running; backend/frontend/PostgreSQL/Kafka healthy |
| Actuator | UP |
| Prometheus target | Up |
| Grafana datasource/dashboard | OK; 10 provisioned queries successful |
| Git whitespace check | Passed |

Backend coverage includes registration default, direct API denials, both next-level requests, forged escalation fields, exact/cross-group review, ADMIN visibility/intervention, rejection/re-request, concurrent terminal transitions, stale reviewer/requester roles, audit actors, refreshed session roles, notification timing/failure, last-admin, and CSRF regression. Frontend coverage includes NO_ACCESS route gating/no data calls, request/pending/rejected states, member review APIs and navigation, role refresh, and all earlier behavior.

Intentional test updates preserve assertions while accounting for NO_ACCESS, V9, group routing, and the new last drawer link. An intermediate concurrent run hit existing frontend timing failures; the final suite without competing builds passed. No unrelated test assertions were removed.

## Browser/manual checks

Actual Edge verified the deployed public registration guidance, SignalForge title, and SVG favicon served successfully through Nginx. No account was registered for visual verification. Browser processes were closed.

Manual: live browser sign-in and protected admission/review flows for NO_ACCESS, VIEWER, OPERATOR, and ADMIN, because persistent synthetic passwords were unavailable and no personal credentials were requested. PostgreSQL-backed authenticated MockMvc tests and frontend behavior tests verify these paths automatically, but are not claimed as live browser authentication. Actual SMTP provider delivery also requires authorized configuration and a delivery check. No real email was sent by automated tests.

## Files changed in this task

Created:

- backend/src/main/resources/db/migration/V9__add_no_access_and_viewer_requests.sql
- frontend/src/pages/GroupAccessRequests.tsx
- docs/ACCESS_GOVERNANCE.md
- docs/ACCESS_GOVERNANCE_VERIFICATION.md
- .env (ignored local configuration; no passwords)

Modified:

- backend/src/main/java/com/kritika/signalforge/auth/UserRole.java
- backend/src/main/java/com/kritika/signalforge/auth/AppUser.java
- backend/src/main/java/com/kritika/signalforge/auth/AccessRequest.java
- backend/src/main/java/com/kritika/signalforge/auth/AccessRequestRepository.java
- backend/src/main/java/com/kritika/signalforge/auth/AccessRequestService.java
- backend/src/main/java/com/kritika/signalforge/auth/AccessRequestController.java
- backend/src/main/java/com/kritika/signalforge/auth/AccessRequestCreated.java
- backend/src/main/java/com/kritika/signalforge/auth/AccessRequestNotificationService.java
- backend/src/main/java/com/kritika/signalforge/config/SecurityConfig.java
- backend/src/test/java/com/kritika/signalforge/auth/AccessGovernanceIntegrationTests.java
- backend/src/test/java/com/kritika/signalforge/auth/AuthenticationIntegrationTests.java
- backend/src/test/java/com/kritika/signalforge/auth/RbacIntegrationTests.java
- backend/src/test/java/com/kritika/signalforge/auth/AccessRequestNotificationServiceTests.java
- backend/src/test/java/com/kritika/signalforge/auth/AccessRequestNotificationIntegrationTests.java
- frontend/src/types.ts
- frontend/src/App.tsx
- frontend/src/api/governance.ts
- frontend/src/auth/permissions.ts
- frontend/src/auth/AuthRoutes.tsx
- frontend/src/pages/Access.tsx
- frontend/src/pages/AdminAccessRequests.tsx
- frontend/src/pages/AdminUsers.tsx
- frontend/src/pages/AuthPage.tsx
- frontend/src/test/Governance.test.tsx
- frontend/src/test/Navigation.test.tsx
- docs/ACCESS_REQUEST_EMAIL.md

These lists are scoped to this task; earlier Prompt 26/26.1 changes remain uncommitted. Some reused files are still Git-untracked because they were created in Prompt 26.1. Temporary maintenance/browser/test evidence remains ignored under backend/target/.

No ADMIN request workflow, automatic first-member grants, unrelated features, UI redesign, or new permanent infrastructure. ADMIN retains all-request visibility and is not routinely emailed when the target group exists. Nothing committed or pushed.
