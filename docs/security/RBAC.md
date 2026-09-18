# Role-based access control (Prompt 24)

Authentication answers **"Who are you?"** Authorization answers **"What are you allowed to do?"** SignalForge retains Spring Security session authentication and CSRF protection. Backend request authorization is the security boundary; React controls are presentation only.

## Roles and persistence

Each application user has one UserRole enum: VIEWER, OPERATOR or ADMIN. Flyway V7__add_user_roles.sql adds a non-null VARCHAR(16) role with default VIEWER and a database CHECK constraint for the three values. Existing users migrate to VIEWER. Earlier migrations and existing application records are unchanged.

Public registration always constructs a VIEWER on the server. The registration DTO has no role field; supplied role fields are ignored and cannot promote an account. /auth/register, /auth/login and /auth/me return the safe user DTO with id, email, displayName, createdAt and role. Password hashes and session internals remain private.

| Human capability | VIEWER | OPERATOR | ADMIN |
| --- | --- | --- | --- |
| Read events, incidents and details | Yes | Yes | Yes |
| Acknowledge/resolve incidents | No | Yes | Yes |
| List users and change roles | No | No | Yes |
| Telemetry ingestion as a role privilege | Not assigned | Not assigned | Not assigned |

**Ingestion exception inherited from Prompt 23:** POST /events currently accepts any authenticated session with valid CSRF, independent of role. Therefore VIEWER sessions can still use that endpoint under the inherited development boundary. This prompt deliberately preserves working validation/ingestion semantics; it does **not** claim human roles provide machine authentication or that ADMIN uniquely has ingestion permission. The dashboard has no ingest control. Production machine ingestion needs dedicated service credentials, API keys, mTLS or OAuth client credentials later; none is implemented here.

## Backend enforcement

SecurityConfig makes the policy explicit:
- GET /events, /events/**, /incidents, /incidents/**: VIEWER, OPERATOR or ADMIN.
- PATCH /incidents/{id}/acknowledge and /resolve: OPERATOR or ADMIN.
- /admin/**: ADMIN.
- POST /events: existing authenticated-session requirement, unchanged.
- Health and Prometheus GET endpoints remain public for current local infrastructure.
- Other endpoints retain their previous authenticated behavior.

This uses Spring Security request-level authorization, not scattered controller checks. The role-change service also rechecks the current actor after acquiring the transaction lock, closing the race where an actor was demoted while waiting to update another account.

Anonymous protected requests return JSON 401, including admin mutations with no CSRF token. Authenticated permission failures return JSON 403 with code FORBIDDEN. Missing/invalid CSRF returns 403 with code CSRF_INVALID. Tests deliberately supply valid CSRF for authorization attacks so a CSRF failure cannot masquerade as RBAC enforcement.

## Active sessions

Successful login stores only a stable SessionIdentity (UUID and email) in Spring's authenticated session context. CurrentRoleFilter loads the user's **current database role before authorization on each authenticated request** and builds a fresh request-local SecurityContext. No role cache or permanent session authority snapshot is trusted.

A committed demotion affects the next request using the same cookie. Promotion works without logging in again. Self-demotion does not log the user out. An already-authorized in-flight incident operation may finish if a role changes concurrently; role management itself rechecks the actor inside the serialized transaction. No claim is made that demotion cancels in-flight work.

This adds one user lookup per authenticated application request and depends on database availability. Sessions remain process-local; backend restart still logs users out. No Redis or session-distribution infrastructure was added.

## Local bootstrap administrator

Bootstrap is **disabled unless SIGNALFORGE_BOOTSTRAP_ENABLED=true**. Configure a new, unused synthetic/local email, a password meeting the existing registration rules (8–72 characters and at most 72 UTF-8 bytes), and a display name:

- SIGNALFORGE_BOOTSTRAP_ENABLED
- SIGNALFORGE_BOOTSTRAP_EMAIL
- SIGNALFORGE_BOOTSTRAP_PASSWORD
- SIGNALFORGE_BOOTSTRAP_DISPLAY_NAME

Compose maps these environment variables to the backend. The root .env.example contains only empty fields/default-disabled configuration. Supply credentials in an ignored local .env or the shell environment; never commit them. For example, from the repository root:

```powershell
$env:SIGNALFORGE_BOOTSTRAP_ENABLED = 'true'
$env:SIGNALFORGE_BOOTSTRAP_EMAIL = Read-Host 'New local admin email'
$secret = Read-Host 'Temporary admin password' -AsSecureString
$env:SIGNALFORGE_BOOTSTRAP_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
$env:SIGNALFORGE_BOOTSTRAP_DISPLAY_NAME = 'Local Administrator'
docker compose up -d --build backend frontend
```

The startup runner creates exactly that account as ADMIN using BCrypt. It is idempotent: if the account is already ADMIN and the supplied password matches, it does nothing and does not reset the password/name. It refuses an existing non-admin identity, including a public registration or an account subsequently demoted. It also rejects invalid configuration or incompatible credentials with a generic error. It never promotes every existing user, guesses a personal identity or makes the first registration ADMIN.

After verifying login, disable bootstrap and remove the credentials:

```powershell
Remove-Item Env:SIGNALFORGE_BOOTSTRAP_ENABLED
Remove-Item Env:SIGNALFORGE_BOOTSTRAP_EMAIL
Remove-Item Env:SIGNALFORGE_BOOTSTRAP_PASSWORD
Remove-Item Env:SIGNALFORGE_BOOTSTRAP_DISPLAY_NAME
docker compose up -d backend frontend
```

If using .env, remove the credential entries there too. Recreating the backend removes the bootstrap secret from its container configuration and logs out existing process-local sessions; log in again. Do not print docker compose config while bootstrap secrets are supplied, because Compose expands environment values. Docker administrators can inspect a container's environment; bootstrap is intended for trusted local development.

## User management

- GET /admin/users: ADMIN-only safe user list, ordered by email.
- PATCH /admin/users/{id}/role: ADMIN-only JSON body such as {"role":"OPERATOR"}.
- Success: 200 safe updated user DTO.
- 400: malformed/missing/unknown role, including numeric role values.
- 401: anonymous.
- 403: authenticated non-admin or an actor demoted before the transaction's current-role check.
- 404: target UUID does not exist.
- 409: attempting to demote the last ADMIN.

Admins may change VIEWER/OPERATOR/ADMIN roles, including their own. Self-demotion is allowed only while another ADMIN remains. Setting the current role again is harmless. A stale former admin cannot demote the only remaining admin: the request is forbidden, rather than treating the stale session as an administrator.

Role changes and bootstrap share a single PostgreSQL transaction-level advisory lock, key 73402624. RoleChangeLock requires an existing transaction. After taking the lock, the service discards any pre-lock JPA snapshot, re-reads the actor and target, and counts current admins before saving. The lock lasts through commit/rollback. This serializes role changes across backend instances using the same database, without a new locking service. Direct database writes that bypass this service do not honor the application invariant; operators must not manually demote/delete the last admin.

Concurrent cross-demotion integration tests prove that one actor can succeed and the actor it demoted is then rejected. Tests do not assume the development database contains no other admins. The last-admin rule also has focused service tests and is verified against the live initial bootstrap admin.

The service receives actor identity and target identity and owns the old/new role boundary, so Prompt 25 can add audit recording inside that transaction later. No audit persistence or access-request workflow is included now.

## Frontend

User.role is a typed VIEWER | OPERATOR | ADMIN union. Central canManageIncidents/canManageUsers helpers control display. VIEWER sees read-only incident detail rather than disabled mutation buttons. OPERATOR keeps the operational workflow and has no admin navigation. ADMIN sees an Admin link to /admin/users.

The admin page lists safe identity/current role/creation time and uses labeled selects plus deliberate Save role buttons. Pending requests disable duplicate submission. No optimistic permission update occurs: saved server responses replace displayed state. Last-admin conflicts and other errors are visible.

A successful self-demotion immediately updates the authenticated user from the server response, removes Admin navigation and replaces the current admin page with Access denied. Non-admin direct routes show a distinct 403 page without fetching the user list. A backend FORBIDDEN response refreshes /auth/me to discard stale UI permissions; CSRF failures remain separate. Changes made by another admin need a new /auth/me response (refresh/session restoration or a permission rejection) to update already-rendered controls; the backend enforces the new role on the next request regardless.

CSRF retrieval and credentials remain centralized. Admin JSON PATCH uses the same cookie/token transport as incident mutations. Development CORS adds only GET /admin/users and PATCH /admin/users/{id}/role for the existing explicit localhost:5173 origin.

## Verification and boundaries

The baseline was 114 backend, 50 frontend and 10 validation tests. See [RBAC verification](RBAC_VERIFICATION.md) for final counts, file lists, live role results, migration/data preservation and final service health.

The validation scripts retain the existing authenticated ingestion path and require no RBAC bypass or rewrite. No per-user metrics or sensitive request logging is added.

Known limitations: process-local sessions, the inherited machine-identity gap on ingestion, open local registration without email verification, no fine-grained/resource/tenant authorization, and no access approval/audit workflow. Production needs HTTPS/Secure cookies and appropriate session distribution. Access requests and audit persistence are explicitly deferred to Prompt 25; no SSO/JWT rewrite or visual redesign is included.

References: [Spring request authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html), [PostgreSQL advisory locks](https://www.postgresql.org/docs/16/explicit-locking.html#ADVISORY-LOCKS).
