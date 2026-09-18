import { Link } from 'react-router';
import type { Event, Incident } from '../types';
import { formatTime } from '../utils/format';
import { SeverityBadge, StatusBadge } from './Shared';

export function EventsTable({ events, compact = false }: { events: Event[]; compact?: boolean }) {
  return <div className="table-scroll" tabIndex={0} role="region" aria-label="Events table"><table>
    <thead><tr><th scope="col">Service</th><th scope="col">Type</th><th scope="col">Severity</th>{!compact && <th scope="col">Message</th>}<th scope="col">Event Time</th>{!compact && <th scope="col">Received At</th>}</tr></thead>
    <tbody>{events.map(event => <tr key={event.id}><td className="service" title={`Event ID: ${event.id}`}><span className="truncate" title={event.service} tabIndex={0}>{event.service}</span></td><td className="mono"><span className="truncate" title={event.type} tabIndex={0}>{event.type}</span></td><td><SeverityBadge value={event.severity} /></td>
      {!compact && <td><span className="truncate" title={event.message} tabIndex={0}>{event.message}</span></td>}<td className="date"><time dateTime={event.timestamp}>{formatTime(event.timestamp)}</time></td>
      {!compact && <td className="date"><time dateTime={event.receivedAt}>{formatTime(event.receivedAt)}</time></td>}</tr>)}</tbody>
  </table></div>;
}
export function IncidentsTable({ incidents }: { incidents: Incident[]; compact?: boolean }) {
  return <div className="table-scroll" tabIndex={0} role="region" aria-label="Incidents table"><table>
    <thead><tr><th scope="col">Service</th><th scope="col">Type</th><th scope="col">Severity</th><th scope="col">Status</th><th scope="col">Title</th><th scope="col">Created At</th><th scope="col">Details</th></tr></thead>
    <tbody>{incidents.map(incident => <tr key={incident.id} className={"incident-" + incident.status.toLowerCase()}><td className="service" title={`Incident ID: ${incident.id}`}><span className="truncate" title={incident.service} tabIndex={0}>{incident.service}</span></td><td className="mono"><span className="truncate" title={incident.type} tabIndex={0}>{incident.type}</span></td><td><SeverityBadge value={incident.severity} /></td><td><StatusBadge value={incident.status} /></td>
      <td><span className="truncate" title={incident.title} tabIndex={0}>{incident.title}</span></td><td className="date"><time dateTime={incident.createdAt}>{formatTime(incident.createdAt)}</time></td><td><Link className="text-link" to={`/incidents/${encodeURIComponent(incident.id)}`} aria-label={`View incident ${incident.id}`}>View</Link></td></tr>)}</tbody>
  </table></div>;
}
