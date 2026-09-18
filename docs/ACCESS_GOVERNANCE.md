# V1 access governance

New registrations start as NO_ACCESS. They can authenticate and view My Access, but cannot read or ingest operational data, perform incident actions, or use administrative APIs. Existing accounts retain their roles after V9.

| Current role | Server-derived next request | Review authority |
| --- | --- | --- |
| NO_ACCESS | VIEWER | None |
| VIEWER | OPERATOR | VIEWER requests only |
| OPERATOR | No request | OPERATOR requests only |
| ADMIN | No request | All VIEWER/OPERATOR requests; Users and Audit |

ADMIN is explicitly assigned, never requested. Approval ownership is based on the actual requested group, not the operational capability hierarchy. Members review their group's pending requests at `/access/review`, backed by `GET /access-requests/review` and `PATCH /access-requests/{id}/approve` or `/reject`. The server derives the queue group from the authenticated account; client role/filter values cannot widen it. ADMIN keeps the existing full console and `/admin/access-requests` APIs for all statuses and intervention.

Creation continues to ignore client-supplied requester, status, target role, reviewer, and timestamps. The server derives only NO_ACCESS -> VIEWER or VIEWER -> OPERATOR. ADMIN and OPERATOR creation attempts conflict. Only one PENDING request per account is allowed.

Review rechecks current reviewer authority after acquiring the existing role-change lock. It atomically changes the request, grants the requested level when eligible, and records the actual reviewer plus role-change and request audit facts. Self-review is forbidden. Competing/stale terminal reviews conflict. Rejection retains current access and permits a new request. Existing higher access is never downgraded by approval. A requester demoted below eligibility while its request is pending cannot skip a level through stale approval. CurrentRoleFilter refreshes live session authority on subsequent requests; the frontend refreshes its identity through My Access.

Notification uses the existing post-commit listener; see [email configuration](ACCESS_REQUEST_EMAIL.md). Populated groups are notified directly; only empty groups fall back to ADMIN. Mail stays optional and cannot roll back a committed request.

V9 only extends role/request constraints and changes the future account default. V1-V8 are unchanged; no historical user roles are rewritten by migration.

## Local ADMIN ownership

The intended local owner is `kritikaa.srivastavaa@gmail.com`. This is deployment policy, not hardcoded authorization logic or a global one-admin database constraint. Bootstrap still uses SIGNALFORGE_BOOTSTRAP_ENABLED/EMAIL/PASSWORD/DISPLAY_NAME, is disabled by default, and refuses to claim an existing non-admin registration or reset its password. An existing account must be promoted through the audited role-change service by an authorized existing ADMIN or trusted local recovery operator.

The user approved a local handover: promote the existing owner, then demote the two persistent synthetic ADMIN accounts to VIEWER using UserAdministrationService. Preserve their accounts, passwords, access requests, and audit history. Last-admin protection stays authoritative, so promotion precedes demotion. Isolated automated tests may still create temporary ADMIN fixtures and remove only their own records.

This local checkout configures the owner email in an ignored `.env`, with startup bootstrap disabled and no bootstrap password configured. Existing ownership was established through the approved audited role-change service, not by claiming a public registration.
