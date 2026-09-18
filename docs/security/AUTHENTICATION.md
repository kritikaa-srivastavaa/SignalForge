# Authentication foundation (Prompt 23)

> This records the Prompt 23 foundation. Prompt 24 now extends it with [RBAC](RBAC.md); current user responses also include role, and application permissions are role-dependent.

SignalForge uses Spring Security 7.1.1 (managed by Spring Boot 4.1.1), BCrypt, and process-local servlet sessions. The browser talks to Nginx at http://localhost:5173 and uses its existing /api proxy. The server session is authoritative; React stores only the safe user representation in memory. No JWT, browser-storage auth flags, product roles, or access governance are implemented.

Prompt 23 authentication answers **"Who are you?"** Prompt 24 authorization will answer **"What are you allowed to do?"** All authenticated users currently share application permissions.

## Database and passwords

Flyway V6__create_app_users_table.sql adds only:

```sql
CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(60) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_app_users_email UNIQUE (email)
);
```

Hibernate generates UUIDs; @PrePersist sets createdAt. Email is trimmed and lowercased with Locale.ROOT before validation and persistence. The database uniqueness constraint is authoritative, including concurrent registrations; its violation maps to 409. V1–V5 and existing application tables are unchanged.

BCryptPasswordEncoder uses its default strength 10 and per-password salt. Registration requires a nonblank valid email (up to 254 characters), trimmed nonblank display name (up to 100), and nonblank password (8–72 characters **and at most 72 UTF-8 bytes**). Passwords are never trimmed or truncated. No artificial complexity rules apply.

Safe user responses contain exactly id, email, displayName, createdAt. Entities are never returned. Password-bearing DTOs redact toString; validation errors never serialize rejected values. HTTP request-detail logging and Hibernate bind/extract logging are disabled. No per-user metrics were added.

## HTTP contract

Backend paths below have /api prefixed when called through Nginx.

| Method/path | Request | Success | Other expected responses |
| --- | --- | --- | --- |
| GET /auth/csrf | None | 200: headerName and token | — |
| POST /auth/register | email, password, displayName JSON | 201: safe user | 400 invalid input, 409 duplicate email, 403 missing/invalid CSRF |
| POST /auth/login | email, password JSON | 200: safe user and authenticated session | 401 generic "Invalid email or password"; 400 malformed/invalid input; 403 CSRF |
| GET /auth/me | Session cookie | 200: safe user | 401 anonymous/expired session |
| POST /auth/logout | Session cookie and CSRF header | 204, session invalidated and cookie cleared | 403 CSRF |

**Registration does not authenticate.** The UI confirms account creation and asks the user to log in.

GET /events, GET /events/{id}, POST /events, GET /incidents, GET /incidents/{id}, and both incident lifecycle PATCH endpoints require a session. Anonymous requests return JSON 401 without HTML redirects, including anonymous mutations without a CSRF token. Authenticated mutations without a valid CSRF token return JSON 403.

GET /actuator/health and GET /actuator/prometheus remain public for the current local infrastructure. Other exposed actuator endpoints require authentication. This local observability policy is not an Internet-facing deployment policy.

## Session and CSRF

The default JSESSIONID cookie is HttpOnly, SameSite=Lax, and non-Secure for local HTTP. URL-based session tracking is disabled. Enable server.servlet.session.cookie.secure=true when deploying behind HTTPS.

The JSON login controller authenticates through AuthenticationManager/DaoAuthenticationProvider, invokes Spring's ChangeSessionIdAuthenticationStrategy and CsrfAuthenticationStrategy, and explicitly saves the SecurityContext through Spring's SecurityContextRepository. Successful login rotates the pre-login session ID and clears the old CSRF token. The standard Spring logout filter handles invalidation, context cleanup and cookie deletion.

CSRF remains enabled for all unsafe methods, including register, login and logout. GET /auth/csrf materializes Spring's session-backed, BREACH-masked token. Clients send it in X-CSRF-TOKEN. React's central client fetches a fresh token before **each** POST/PATCH, so login/logout rotation is handled without browser storage or a token cache. Both token and mutation requests use credentials: "include". GET requests have no mutation-specific headers. Failed mutations are not automatically replayed.

This follows Spring's [session persistence guidance](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html) and [CSRF endpoint pattern](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).

Sessions live in the backend process, not PostgreSQL. Restarting the backend logs users out. Horizontal scaling needs sticky routing or shared session storage; Spring Session with Redis/database or external OIDC could be evaluated later. Neither is included here.

## Browser routing and development

AuthProvider restores GET /auth/me before displaying protected content. It distinguishes 401 from connectivity/server errors and offers Retry for the latter. /login and /register have accessible labels, password autocomplete, pending/error states and duplicate-submit guards. The console displays the user's name/email and a real backend Logout button. Protected API 401 responses remove the console and return to login.

Protected routes: /, /events, /incidents, /incidents/:id. Login preserves only known internal application paths, never arbitrary external destinations. Logout removes protected content only after the server logout succeeds.

For host Vite development, the only permitted origin is http://localhost:5173, with credentials enabled. CORS allows:
- GET: /events, /events/{id}, /incidents, /incidents/{id}, /auth/me, /auth/csrf.
- POST: /events, /auth/register, /auth/login, /auth/logout.
- PATCH: /incidents/{id}/acknowledge, /incidents/{id}/resolve.
- Request headers: Content-Type and X-CSRF-TOKEN.

CORS does not replace CSRF. Untrusted origins and unrelated methods/headers are rejected. Use localhost consistently rather than mixing localhost and 127.0.0.1.

The existing Nginx proxy forwards Set-Cookie, Cookie, POST/PATCH and CSRF headers normally; no separate authentication proxy is needed.

## Validation tools

Register a local synthetic account using the UI. Supply credentials only in the current shell environment, preferably via a password-manager/secure prompt:

```powershell
$env:SIGNALFORGE_AUTH_EMAIL = Read-Host 'Local verification email'
$secret = Read-Host 'Local verification password' -AsSecureString
$env:SIGNALFORGE_AUTH_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
python -B scripts/validation/load.py --requests 1 --concurrency 1
# Larger historical suite, only when deliberately requested:
# python -B scripts/validation/validate.py --output backend/target/validation-auth.json
Remove-Item Env:SIGNALFORGE_AUTH_PASSWORD
Remove-Item Env:SIGNALFORGE_AUTH_EMAIL
```

No credentials, cookies or CSRF tokens are written to evidence. SessionClient logs in, keeps its cookie jar and tokens in memory, and refreshes CSRF after login. The coordinated runner explicitly logs back in after backend restart. No API bypass or security exemption exists. Failed requests are recorded without automatically replaying ingestion. The existing IP rate limiter is unchanged.

The Prompt 22 report and evidence remain historical, measured before authentication/V6; its original commands now require the environment variables above. Outage scenarios still require --include-failures. Authentication verification uses a small probe, not the full outage suite.

## Verification commands

```powershell
# backend (Java 21, PostgreSQL/Kafka running)
.\mvnw.cmd '-Duser.timezone=Asia/Kolkata' verify
# frontend (Node 24)
npm run test
npm run build
# root
python -B -m unittest discover -s scripts/validation -v
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

Business integration tests use Spring Security's mock-user/CSRF support with the actual filter chain. AuthenticationIntegrationTests exercises real password verification, database users, session rotation, token rotation, logout and protected endpoints. Its own synthetic records are removed by exact IDs/email after each test. Frontend authentication tests exercise the actual provider, routes and centralized HTTP client. Existing business tests retain their behavioral assertions inside an authenticated boundary.

Vitest uses one thread worker because fork workers repeatedly timed out on the Windows/Docker development machine.

## Scope and limitations

This is a local authentication foundation. There is no RBAC, account approval, audit trail, login throttling, password reset, MFA or email verification. Registration is open to callers of the local application and does not establish email ownership. The existing ingestion rate limiter remains based on remote IP. Future production work must address HTTPS/Secure cookies and session distribution. No seventh Docker service is introduced.


## Verified result
See [the completion report](VERIFICATION.md) for final test counts, Docker/browser/monitoring results, exact file lists, retained synthetic rows and security checks.
