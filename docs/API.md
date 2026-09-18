# API reference

Base URL: `http://localhost:8080`. Docker browser clients use `http://localhost:5173/api` with the same paths below. JSON examples illustrate contracts, not production records.

## Session and CSRF

Fetch `GET /auth/csrf` with cookies before mutations; response is `{ "headerName": "X-CSRF-TOKEN", "token": "..." }`. Send the returned header/token and cookies on POST/PATCH, including login/register/logout. Login rotates session/CSRF state: fetch a new token afterward. Use a cookie-aware client (`credentials: 'include'` in fetch). No bearer-token API is implemented.

| Method / path | Access | Purpose / normal status |
| --- | --- | --- |
| GET /auth/csrf | Public | CSRF token, 200 |
| POST /auth/register | Public + CSRF | Create NO_ACCESS account, 201; duplicate 409 |
| POST /auth/login | Public + CSRF | Authenticate session, 200; bad credentials 401 |
| GET /auth/me | Authenticated | Current identity/role, 200 |
| POST /auth/logout | Session + CSRF | Invalidate session, 204 |

Registration body: `{ "email": "demo@example.invalid", "password": "<locally supplied password>", "displayName": "Demo" }`. Login omits displayName. Identity response contains `id`, `email`, `displayName`, `role`; never password/hash. Passwords require 8?72 characters and no more than 72 UTF-8 bytes.

## Events

All event endpoints require VIEWER, OPERATOR, or ADMIN. NO_ACCESS is denied.

| Method / path | Purpose / statuses |
| --- | --- |
| POST /events | Persist then publish; 201; validation 400; local limiter 429 |
| GET /events | Paginated exact filters: service, type, severity, page, size; 200 |
| GET /events/{uuid} | Event detail; 200 or 404 |

```json
{
  "service": "payment-service",
  "type": "API_ERROR",
  "severity": "HIGH",
  "message": "Payment gateway timed out",
  "timestamp": "2026-09-14T10:30:00Z"
}
```

Strings must not be blank; timestamp is required. Response repeats submitted fields and adds generated UUID `id` and backend Instant `receivedAt`. Severity/type are strings, not enums. POST waits for broker acknowledgement, but database/Kafka commit is not atomic. Rate limit is 10 accepted requests per backend-visible remote address per fixed 60-second window.

## Incidents

| Method / path | Role | Purpose / statuses |
| --- | --- | --- |
| GET /incidents | VIEWER/OPERATOR/ADMIN | Filters service/type/severity/status/page/size; 200 |
| GET /incidents/{uuid} | VIEWER/OPERATOR/ADMIN | Detail; 200 or 404 |
| PATCH /incidents/{uuid}/acknowledge | OPERATOR/ADMIN | No body; 200; resolved transition/conflicting update 409 |
| PATCH /incidents/{uuid}/resolve | OPERATOR/ADMIN | No body; 200; conflicting update 409 |

Response fields: `id`, `sourceEventId`, `service`, `type`, `severity`, `title`, `status`, `createdAt`. Status is OPEN, ACKNOWLEDGED, or RESOLVED. Repeating an already satisfied lifecycle action returns the current state; acknowledging a resolved incident conflicts.

## Access requests

| Method / path | Role | Purpose / statuses |
| --- | --- | --- |
| POST /access-requests | NO_ACCESS/VIEWER | Derive next VIEWER/OPERATOR target; 201; pending/ineligible 409 |
| GET /access-requests/me | Authenticated | Latest own request, 200; none 204 |
| GET /access-requests/review | VIEWER/OPERATOR | Paginated pending requests for exact reviewer group, 200 |
| PATCH /access-requests/{uuid}/approve | Exact target group or ADMIN | Review, 200; unauthorized 403, missing 404, stale/ineligible 409 |
| PATCH /access-requests/{uuid}/reject | Exact target group or ADMIN | Review without grant, same major statuses |

Creation has no required body; client target/identity/status fields cannot escalate privileges. Reject accepts an optional `{ "reason": "Approved after review" }` body with at most 300 characters. Self-review is forbidden. Response includes `id`, `requesterId`, `requesterEmail`, `requestedRole`, `status`, `createdAt`, `reviewedAt`, `reviewedBy`, `reviewReason`. Approval has no request body or reason. Group members do not choose their queue's group. ADMIN uses the separate list below.

## Administration

All endpoints require ADMIN.

| Method / path | Purpose / statuses |
| --- | --- |
| GET /admin/users | Identity list (JSON array), 200 |
| PATCH /admin/users/{uuid}/role | Body `{ "role": "VIEWER" }`; 200, missing 404, last-admin demotion 409 |
| GET /admin/access-requests | All groups; optional status/page/size, 200 |
| PATCH /admin/access-requests/{uuid}/approve | No body; same review rules/statuses |
| PATCH /admin/access-requests/{uuid}/reject | Optional reason; same review rules/statuses |
| GET /admin/audit | Paginated audit records; optional action/page/size, 200 |

Role values are NO_ACCESS, VIEWER, OPERATOR, ADMIN. ADMIN assignment is audited, not an access request target. Role updates do not alter passwords.

## Pagination and common errors

Paginated collections return `{ "content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0, "first": true, "last": true }`. Page is zero-based; negatives clamp to zero. Size defaults to 20, nonpositive values become 20, and maximum is 100. Operational lists sort by descending timestamp/createdAt with UUID tie-breaker. Blank string filters are ignored; filters match exactly, not substring search. `/access-requests/me` returns one latest request or 204; `/admin/users` returns an array.

Common statuses: 400 invalid JSON/UUID/validation; 401 unauthenticated; 403 forbidden or invalid CSRF; 404 missing record; 409 state/optimistic-lock/governance conflict; 429 ingestion rate limit. Error payloads differ by existing handler; no uniform RFC problem-details contract is promised. `GET /actuator/health` and `GET /actuator/prometheus` are public local operational endpoints. No event delete/update API, Kafka-consumer API, or DLT replay endpoint exists.
