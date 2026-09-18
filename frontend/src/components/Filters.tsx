import { useState } from 'react';
import type { Filters as FilterValues, IncidentStatus } from '../types';

export const emptyFilters: FilterValues = { service: '', type: '', severity: '', status: '' };
export function Filters({ incidents = false, onApply }: { incidents?: boolean; onApply: (filters: FilterValues) => void }) {
  const [draft, setDraft] = useState<FilterValues>(emptyFilters);
  return <form className="filters" onSubmit={event => { event.preventDefault(); onApply({ ...draft }); }}>
    <div className="filter-fields">
      <label>Service<input value={draft.service} placeholder="All services" onChange={event => setDraft({ ...draft, service: event.target.value })} /></label>
      <label>Type<input value={draft.type} placeholder="All event types" onChange={event => setDraft({ ...draft, type: event.target.value })} /></label>
      <label>Severity<input list="severity-options" value={draft.severity} placeholder="All severities" onChange={event => setDraft({ ...draft, severity: event.target.value })} /></label>
      <datalist id="severity-options"><option value="HIGH" /><option value="MEDIUM" /><option value="LOW" /></datalist>
      {incidents && <label>Status<select value={draft.status} onChange={event => setDraft({ ...draft, status: event.target.value as IncidentStatus | '' })}>
        <option value="">All statuses</option><option value="OPEN">Open</option><option value="ACKNOWLEDGED">Acknowledged</option><option value="RESOLVED">Resolved</option>
      </select></label>}
    </div>
    <div className="filter-actions"><span className="filter-hint">Filters match exact values. Severity also accepts custom values.</span><button className="button text-button" type="button" onClick={() => { setDraft(emptyFilters); onApply({ ...emptyFilters }); }}>Clear Filters</button><button className="button primary" type="submit">Apply Filters</button></div>
  </form>;
}
