# Application audit trail (Prompt 25)

The PostgreSQL audit_records table answers **who did what, to which resource, and when** for access governance and human incident mutations. It is an **application-level append-only audit trail**, not a tamper-proof ledger or compliance certification.

## Deliberate data model

Each row has a generated UUID id, actor_id (app_users FK), actor_email snapshot, typed action, typed target_type, target_id UUID, timestamp Instant, and optional old_value/new_value strings bounded to 32 characters. Actor email is captured at the time of the action; it is not rewritten if an identity later changes. User deletion is not implemented and the FK restricts deletion of referenced actors.

Target types are USER, ACCESS_REQUEST and INCIDENT. Target IDs identify distinct domain tables, so there is no polymorphic target FK. Values are deliberate role/status enum names, not arbitrary request bodies or JSON metadata. Access-review reasons live in the request itself, not in audit metadata.

Passwords, hashes, cookies, CSRF tokens, session IDs, credentials and telemetry payloads are never passed to audit recording or returned by the audit DTO. Audit identities are personal data, accessible only to administrators. Do not treat actor-email snapshots as secrets, but protect database access and backups appropriately.

## Recorded facts

| Action | Target | When |
| --- | --- | --- |
| ACCESS_REQUEST_CREATED | ACCESS_REQUEST | A new PENDING request is saved |
| ACCESS_REQUEST_APPROVED | ACCESS_REQUEST | PENDING becomes APPROVED |
| ACCESS_REQUEST_REJECTED | ACCESS_REQUEST | PENDING becomes REJECTED |
| USER_ROLE_CHANGED | USER | A manual change or approval actually changes a role |
| INCIDENT_ACKNOWLEDGED | INCIDENT | OPEN becomes ACKNOWLEDGED |
| INCIDENT_RESOLVED | INCIDENT | OPEN/ACKNOWLEDGED becomes RESOLVED |

Normal approval produces an approval fact plus **one** role-change fact. Already-satisfied approval does not produce a fake role change. Setting an unchanged role does not produce another role-change fact. Repeated no-op lifecycle actions do not produce duplicate success records; forbidden, invalid or rolled-back operations never produce a success fact.

Registration, login/logout, bootstrap account creation, reads, forbidden attempts, ingestion, Kafka delivery and automatic incident creation are not audited in this V1 scope. Automated actions are not attributed to a fake human. No generic request logger is introduced.

## Persistence and transaction boundary

AuditService centralizes inserts using Spring JdbcTemplate, sharing the same PostgreSQL datasource/transaction as JPA. record and recordCurrentActor require an existing transaction (MANDATORY). Business services supply typed action/target and bounded values; controllers do not construct or save audit rows.

The role/request mutation and its audit insert commit or roll back together. Human incident transitions flush their existing optimistic version check and insert audit before commit. A competing version failure or audit error rolls back the whole operation. Audit exceptions are not swallowed or handled in a separate transaction.

There is no update/delete method or mutable JPA audit entity in the application. The read API returns AuditResponse records. No UI edit/delete/clear/export functionality exists. Database administrators/superusers can still change records; database append-only privileges, external storage, signatures and tamper detection are not implemented.

## Read API and UI

GET /admin/audit?page=0&size=20&action=USER_ROLE_CHANGED is ADMIN-only. Action is optional and enum-validated. Ordering is timestamp DESC, id DESC; pagination uses the existing PageResponse, size defaults to 20 and caps at 100. Indexes cover time/id and action/time/id. Arbitrary actor search/full-text search is not included.

Anonymous access returns 401; VIEWER/OPERATOR return 403. POST/PATCH/DELETE on the audit collection are unsupported (405 for an authorized ADMIN with CSRF). No mutation route is defined.

The ADMIN Audit Log screen shows Time, Actor, Action, Target and old/new values, with backend pagination and an action filter. It renders deliberate readable fields rather than raw JSON. Existing role navigation, direct-route forbidden behavior and session handling remain in force.

## Limitations

Retention is indefinite for V1: no purge job, archive, export, legal hold or retention-policy enforcement. Table growth will require an explicit later retention decision. Actor email snapshots may outlive identity changes. Timestamp ordering uses application clocks; it is not a total cross-node commit order. No audit events are sent to Kafka or Prometheus, and no identity is used as a metric label.

See [access governance](ACCESS_GOVERNANCE.md) and [verification](ACCESS_GOVERNANCE_VERIFICATION.md).
