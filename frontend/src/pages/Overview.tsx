import { useCallback } from 'react';
import { Link } from 'react-router';
import { getEvents } from '../api/events';
import { getIncidents } from '../api/incidents';
import { EmptyState, ErrorState, LoadingState, PageHeader, Panel } from '../components/Shared';
import { EventsTable, IncidentsTable } from '../components/Tables';
import { useRemote } from '../hooks/useRemote';
import { formatCount, timeZone } from '../utils/format';

export function Overview() {
  const load = useCallback(async (signal: AbortSignal) => {
    // Recent pages already contain totals, avoiding two extra count requests.
    const [events, incidents, open, acknowledged, resolved] = await Promise.all([
      getEvents({ page: 0, size: 5 }, signal),
      getIncidents({ page: 0, size: 5 }, signal),
      getIncidents({ page: 0, size: 5, status: 'OPEN' }, signal),
      getIncidents({ page: 0, size: 1, status: 'ACKNOWLEDGED' }, signal),
      getIncidents({ page: 0, size: 1, status: 'RESOLVED' }, signal),
    ]);
    return { events, incidents, open, acknowledged, resolved };
  }, []);
  const { data, error, loading, reload } = useRemote(load);
  return <>
    <PageHeader title="Overview" description="Incidents requiring attention and recent telemetry across your services." onRefresh={reload} loading={loading} />
    {loading ? <LoadingState /> : error ? <ErrorState resource="overview" detail={error} retry={reload} /> : data && <>
      <div className="summary-grid">
        {[
          { label: 'Open', count: data.open.totalElements, note: 'Awaiting attention', tone: 'danger' },
          { label: 'Acknowledged', count: data.acknowledged.totalElements, note: 'Under investigation', tone: 'warning' },
          { label: 'Resolved', count: data.resolved.totalElements, note: 'Marked as resolved', tone: 'success' },
          { label: 'Total Events', count: data.events.totalElements, note: 'Telemetry received', tone: '' },
          { label: 'Total Incidents', count: data.incidents.totalElements, note: 'Across all statuses', tone: '' },
        ].map(card => <section className={`summary-card ${card.tone}`} aria-label={card.label} key={card.label}>
          <h2>{card.label}</h2><strong>{formatCount(card.count)}</strong><span>{card.note}</span>
        </section>)}
      </div>
      <Panel title="Needs attention" action={<Link className="text-link" to="/incidents">Inspect incidents</Link>}>
        {data.open.content.length ? <IncidentsTable incidents={data.open.content} compact /> : <div className="state"><strong>No open incidents</strong><p>No incidents are currently awaiting attention.</p></div>}
      </Panel>
      <div className="section-intro"><div><p className="eyebrow">LATEST ACTIVITY</p><h2>Recent activity</h2></div><span className="subtle">Times shown in {timeZone}</span></div>
      <Panel title="Recent Incidents" action={<Link className="text-link" to="/incidents">View all incidents <span aria-hidden="true">↗</span></Link>}>
        {data.incidents.content.length ? <IncidentsTable incidents={data.incidents.content} compact /> : <EmptyState resource="incidents" />}
      </Panel>
      <Panel title="Recent Events" action={<Link className="text-link" to="/events">View all events <span aria-hidden="true">↗</span></Link>}>
        {data.events.content.length ? <EventsTable events={data.events.content} compact /> : <EmptyState resource="events" />}
      </Panel>
    </>}
  </>;
}
