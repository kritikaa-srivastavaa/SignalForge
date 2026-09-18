# Access request email notifications

A newly committed access request notifies actual members of its requested role: VIEWER for NO_ACCESS admission, or OPERATOR for VIEWER escalation. If that exact group has zero members, notification falls back to current ADMIN users. ADMIN is not routinely emailed when the requested group has members, but retains all-request visibility and emergency review authority. Higher operational capability does not imply cross-group approval ownership.

Separate plain-text messages contain requester email, request time (UTC), and a review link. Group members use `/access/review`; fallback ADMIN recipients use `/admin/access-requests`. The requester is excluded and duplicate recipient addresses are removed. No credentials or session data are included.

Mail is **disabled by default**. Local development and automated tests require no SMTP credentials. Tests use a mocked mail sender and never send real emails.

## Configuration

Set these in the process environment or an ignored local `.env` for Docker Compose. Do not commit credentials.

| Variable | Default | Purpose |
| --- | --- | --- |
| SIGNALFORGE_MAIL_ENABLED | false | Explicitly enable notifications |
| MAIL_HOST | localhost | SMTP host; configure a reachable provider when enabled |
| MAIL_PORT | 587 | SMTP port |
| MAIL_USERNAME | empty | Provider login, when required |
| MAIL_PASSWORD | empty | Provider secret, when required |
| MAIL_FROM | empty | Provider-approved sender address; required to send |
| MAIL_SMTP_AUTH | true | SMTP authentication; disable only for a trusted local capture server |
| MAIL_STARTTLS_ENABLED | true | Enable and require STARTTLS; configure to match the SMTP server |
| SIGNALFORGE_PUBLIC_URL | http://localhost:5173 | Browser-accessible product base URL used for the review link |

Docker Compose passes these settings to the existing backend container; no additional service was added. For a mail capture server running on the Windows host, the container normally needs `host.docker.internal` rather than `localhost`. Set TLS/authentication to match that local server. No permanent capture server is part of this change.

Spring Boot's managed mail starter provides `JavaMailSender`. Connection/read/write timeouts are each 3 seconds. SMTP health probing is disabled so optional mail cannot make the core application unhealthy. Configuration follows [Spring Boot mail support](https://docs.spring.io/spring-boot/reference/io/email.html).

## Timing and failures

`AccessRequestService` publishes immutable request notification data. A [transaction-bound listener](https://docs.spring.io/spring-framework/reference/7.1/data-access/transaction/event.html) runs only AFTER_COMMIT. Rolled-back, duplicate, or otherwise failed creation does not send a notification. Actual target-group email addresses (or ADMIN fallback addresses) are queried in a fresh read-only transaction; passwords are not selected.

Delivery is synchronous after commit and best effort. Each recipient is isolated so one failure does not stop the others. Provider messages and exception stack traces are omitted from notification failure logs; logs include only the request ID and exception class. A lookup failure or missing sender is also contained. With an empty target group and zero administrators, a safe warning is logged and request creation still succeeds.

**The database and Admin Access Requests queue remain the source of truth.** Delivery failure never changes a committed PENDING request or approval/rejection behavior. There are no automatic retries, durable notification records, or guaranteed delivery; a process interruption after commit can leave a request without email. SMTP latency can delay the HTTP response, but it cannot undo the request.

Real provider delivery requires manual configuration and verification with authorized SMTP settings. No personal credentials are required by this implementation.
