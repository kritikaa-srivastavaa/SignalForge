# Security model

Spring Security authenticates email/password with BCrypt hashes; APIs never return hashes. Password input is validated, including BCrypt's 72-byte UTF-8 limit. Registration requires separate login. Login invokes session fixation protection; logout invalidates the session.

Sessions use HttpOnly, SameSite=Lax cookies. Mutations require a fresh CSRF token and session cookies; browser tokens are not stored in localStorage/sessionStorage. Docker uses a same-origin Nginx proxy; host development allows credentialed CORS only for `http://localhost:5173`.

| Role | Authority |
| --- | --- |
| NO_ACCESS | Own identity and access request only; no operational data/ingestion |
| VIEWER | Operational reads/ingestion; request OPERATOR; review VIEWER requests |
| OPERATOR | Operational reads/ingestion and incident lifecycle; review OPERATOR requests |
| ADMIN | Operational actions, all request intervention, Users, Audit |

New registration always starts NO_ACCESS. Request progression is derived server-side: NO_ACCESS ? VIEWER ? OPERATOR. ADMIN cannot be requested. Only one pending request per account is allowed; self-review is forbidden. Group membership, current requester eligibility, and pending state are rechecked under the role-change lock. Approval and role/audit persistence share a transaction. Rejection preserves access; stale terminal reviews conflict. Higher existing access is never downgraded by approval.

Roles are refreshed server-side on requests. Hidden UI controls are convenience, not the authorization boundary. ADMIN bootstrap is opt-in and refuses to claim an existing non-admin registration or reset credentials. Last-admin protection prevents demotion of the final ADMIN. Application-level append-only audit facts record actor, action, target, role/status changes, time, and optional review reason.

Email is optional and disabled by default. A post-commit listener notifies exact target-group members; ADMIN fallback applies only to an empty target group. Recipients receive individual messages. No passwords, hashes, sessions, or CSRF tokens enter mail; errors do not roll back requests. SMTP credentials belong only in environment/local secret configuration.

## V1 limitations

Local HTTP cookies are not Secure; use HTTPS and Secure cookies for deployment. Sessions are process-local and lost on restart. Local Docker exposes development ports and public database/Grafana defaults; do not expose it to the internet unchanged. Kafka uses plaintext local listeners. Rate limiting is local and keyed by backend-visible remote address; behind Nginx, users may share a bucket. Machine ingestion identities are not implemented. Audit records are not cryptographically tamper-proof or database-enforced immutable storage. No MFA, password recovery, production secret manager, durable mail queue, or distributed session store is claimed.

See [current governance policy](ACCESS_GOVERNANCE.md) and [email configuration](ACCESS_REQUEST_EMAIL.md). Historical prompt verification reports remain evidence of their original baselines, not replacements for this current model.
