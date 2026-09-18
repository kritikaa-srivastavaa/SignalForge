# Portfolio screenshot checklist

Capture real deployed pages; no mock screenshots are provided. Use an authorized account and redact personal email addresses, secrets, cookies, and tokens. Preserve intentional assets/evidence. The UI needs no redesign for these captures.

| File to add here | Screen / exact route |
| --- | --- |
| overview.png | `http://localhost:5173/` ? Overview, sidebar, totals and recent activity |
| events.png | `http://localhost:5173/events` ? submitted demo service, filters and table |
| incident-detail.png | `http://localhost:5173/incidents/{actual-uuid}` ? real incident title/status and lifecycle controls as OPERATOR/ADMIN |
| access-governance.png | `http://localhost:5173/access/review` as VIEWER/OPERATOR, or `/admin/access-requests` as ADMIN |
| grafana.png | `http://localhost:3000` ? Dashboards ? SignalForge Overview; use `http://localhost:3000/d/signalforge-overview` |

The Grafana UID is defined in `infrastructure/grafana/provisioning/dashboards/signalforge-dashboard.json`. Capture after two scrapes; choose a range containing real demo activity. Additional optional captures: `/admin/audit`, `/admin/users`, and mobile Overview.

After adding reviewed images, replace the README checklist link with Markdown image links such as `![Overview](docs/screenshots/overview.png)`. Do not link nonexistent images. Authenticated browser captures remain manual because reusable test-account passwords are not retained.
