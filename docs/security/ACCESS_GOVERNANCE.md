# Access governance (Prompt 25)

SignalForge's normal path from read-only access to incident operations is a request for **OPERATOR**, reviewed by another **ADMIN**. This is a small application workflow, not a general IAM system. Authentication, CSRF, current-role authorization and last-admin protection from [RBAC](RBAC.md) remain authoritative.

## Workflow and schema

Flyway V8 adds access_requests and audit_records without changing V1–V7 or existing rows. Access requests store UUID id, requester_id (user FK), requested_role (always OPERATOR), typed status, created_at, reviewed_at, reviewed_by (user FK), optional review_reason (300 characters), and an optimistic version.

Statuses are PENDING, APPROVED and REJECTED. New requests belong to the authenticated user, and all security-controlled fields are server-generated. POST takes no DTO/body values; extra JSON, including a different requester, ADMIN, APPROVED or review fields, is ignored. Requests never grant ADMIN.

Only current VIEWER users may create a request. OPERATOR/ADMIN receive 409 because their access already satisfies the request. A PostgreSQL partial unique index, uq_access_pending_requester, enforces one PENDING row per requester even outside the service. Sequential/concurrent duplicates return 409. A rejected request stays in history; re-request creates a new UUID. A VIEWER demoted after an earlier approval may also create a new request.

Review consistency and self-review constraints are enforced in SQL. Query indexes support status + creation ordering and requester + creation ordering; the partial index enforces pending uniqueness. All lists use createdAt DESC, id DESC.

## API

| Endpoint | Authorization | Result |
| --- | --- | --- |
| POST /access-requests | Authenticated + CSRF; current VIEWER eligibility | 201 request DTO |
| GET /access-requests/me | Authenticated | 200 latest request, or 204 if none |
| GET /admin/access-requests?status=PENDING&page=0&size=20 | ADMIN | Generic paginated request DTOs |
| PATCH /admin/access-requests/{id}/approve | ADMIN + CSRF | 200 reviewed request DTO |
| PATCH /admin/access-requests/{id}/reject | ADMIN + CSRF | 200 reviewed request DTO |

The current-user endpoint returns the newest historical or pending request by createdAt/id; it never accepts another user's identity. It is not a permission source: the user's current role remains authoritative. Pagination defaults to 20, caps at 100, and normalizes nonpositive size/negative page using the existing helper. Status is optional and enum-validated.

Reject optionally accepts {"reason":"Please clarify operational duties."}. Reasons are deliberately **visible to the requester**, bounded to 300 characters and rendered as plain text. Do not put private internal commentary or secrets there. Approval takes no security data. Response DTOs expose only request identity, requester ID/email, requested role/status, creation/review times, reviewer ID and reason.

Errors: 400 invalid UUID/status/reason/JSON; 401 anonymous; 403 insufficient role or self-review; 404 missing request; 409 duplicate pending, already sufficient access, or already reviewed request. Repeating approval/rejection returns 409, including repeating the same decision; it does not create more audit entries.

## Transactions and concurrent decisions

Creation, review and existing manual role changes share the existing PostgreSQL transaction-level advisory lock (73402624). This serializes the small V1 governance write workload across instances sharing this database. After acquiring it, stale JPA state is cleared and the actor's current role is read again. A demoted reviewer cannot act using previously loaded ADMIN authority.

Approval validates PENDING, existing requester (FK), current role and distinct reviewer. It writes APPROVED + reviewer/time, grants VIEWER -> OPERATOR, and inserts audit facts in **one transaction**. If the requester is already OPERATOR or ADMIN, approval records that access is already satisfied and leaves their role unchanged. It never downgrades ADMIN. A successful grant creates exactly one USER_ROLE_CHANGED and one ACCESS_REQUEST_APPROVED fact; already-satisfied approval creates only the approval fact.

Rejection records REJECTED + reviewer/time/reason and an audit fact. It does not change role: an ordinary requester stays VIEWER; an independently promoted user retains their manually granted role. Self-review is forbidden even if the requester became ADMIN after requesting.

The first terminal decision wins; a later or competing decision receives 409. @Version also guards entity updates outside the serialized service. Audit failures propagate and roll back the whole mutation. Integration tests force audit failures without production hooks and verify rollback of approval/role/audit, rejection, manual role changes and incident changes.

The existing Admin Users page remains an audited administrative override. Last-admin protection is unchanged. Direct SQL that bypasses the service can bypass application locking; database constraints still apply. This is intentionally coarse governance locking, not a distributed IAM platform.

## Frontend and sessions

My access is available to signed-in users. VIEWER sees the operational capability explanation and Request Operator Access. Backend-loaded PENDING state removes the action, survives refresh and prevents repeated submission. Rejected state shows the user-visible reason and allows a new request. Historical approval never overrides a later demotion.

Refresh fetches both the latest request and /auth/me, updates the existing AuthProvider identity, and exposes operational controls after approval without logging out or manipulating browser storage. No polling, WebSockets or optimistic permission grant is used. Backend CurrentRoleFilter still reads the committed role on every authenticated request, regardless of UI freshness.

ADMIN sees Access Requests and Audit Log alongside the existing Admin Users link. The queue defaults to PENDING, supports status filtering/backend pagination and shows requester, requested role, status, times, reviewer and reason. Actions disable while pending; rejection has an optional labeled reason and explicit confirmation. Server responses determine success; 409 refreshes the queue and explains that the stale action was not applied. Non-admin direct routes show the existing forbidden UI and do not fetch privileged data.

## Boundaries

No user deletion, expiry, approval chain, notification, access export, SSO or new credential system. Process-local sessions and local HTTP cookie limitations remain. An already-authorized in-flight incident action can finish after a concurrent demotion; governance rechecks under its transaction lock.

POST /events is unchanged: authenticated session + CSRF, independent of human role. Dedicated service-to-service authentication remains future work. Monitoring is unchanged and no identity/request values were added to metric labels.

See [Audit design](AUDIT.md) and [verification report](ACCESS_GOVERNANCE_VERIFICATION.md).
