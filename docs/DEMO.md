# 5?10 minute demo

Use a local development stack and authorized existing accounts. Do not request personal credentials, reset passwords, or present synthetic data as production traffic. If no authorized account is available, prepare the stack/docs and leave authenticated steps manual.

1. Start `docker compose up -d --build`; show six running services and backend health.
2. Login at `/login`. Explain new registration starts NO_ACCESS and requires group approval.
3. Open `/` as VIEWER/OPERATOR/ADMIN. Show actual totals and recent activity; counts come from PostgreSQL.
4. Use the browser's authenticated session for a small event burst. In DevTools on `http://localhost:5173`, run the sample below; it uses the existing cookie and fetches CSRF for every mutation.
5. Open `/events`, filter the unique service, and show persisted UUID/receivedAt and submitted fields.
6. Wait briefly for Kafka processing; open `/incidents`, filter the same service, then open its actual detail route `/incidents/{uuid}`.
7. As OPERATOR/ADMIN, acknowledge and resolve; explain terminal state and concurrent update conflict behavior.
8. Show `/access` and the progression. As a group member open `/access/review`; as ADMIN open `/admin/access-requests`, `/admin/users`, `/admin/audit`. Do not approve your own request or change owner roles just for a demo.
9. Open Grafana at `http://localhost:3000`, Dashboards ? SignalForge Overview. Allow two Prometheus scrapes (about 30 seconds) for rate panels; metrics are activity estimates, not database totals.

```javascript
const service = `portfolio-demo-${Date.now()}`;
for (let i = 0; i < 3; i++) {
  const csrf = await fetch('/api/auth/csrf', { credentials: 'include' }).then(r => r.json());
  const response = await fetch('/api/events', {
    method: 'POST', credentials: 'include',
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify({ service, type: 'API_ERROR', severity: 'HIGH',
      message: 'Synthetic portfolio demo: payment gateway timeout', timestamp: new Date().toISOString() })
  });
  console.log(response.status, await response.json());
}
console.log('Filter service:', service);
```

This intentionally creates three local events and normally one incident (three same-key events within 60 seconds). Use a unique service to avoid prior cooldown; allow a quiet 60-second rate-limit window before the burst. A 429 is a shared local limiter result, not a reason to disable safeguards. Kafka processing is asynchronous; refresh to observe results. Do not delete existing history afterward or claim this small demo proves production throughput.
