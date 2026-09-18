import { useCallback, useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { canManageUsers } from '../auth/permissions';
import { Forbidden } from '../components/Forbidden';
import { EmptyState, ErrorState, LoadingState, PageHeader, PaginationControls, Panel } from '../components/Shared';
import { auditActions, getAudit } from '../api/governance';
import type { AuditAction } from '../api/governance';
import { useRemote } from '../hooks/useRemote';
import { formatTime } from '../utils/format';

export function AdminAudit() {
  const { user } = useAuth();
  return canManageUsers(user) ? <AuditLog /> : <Forbidden />;
}
function AuditLog() {
  const [action, setAction] = useState<AuditAction | ''>('');
  const [page, setPage] = useState(0);
  const load = useCallback((signal: AbortSignal) => getAudit(action, page, signal), [action, page]);
  const { data, loading, error, reload } = useRemote(load);
  return <>
    <PageHeader title="Audit log" description="Who changed access or incident state, and when. Newest first." loading={loading} onRefresh={reload} />
    <label className="governance-filter">Audit action
      <select value={action} onChange={event => { setAction(event.target.value as AuditAction | ''); setPage(0); }}>
        <option value="">All actions</option>{auditActions.map(value => <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>)}
      </select>
    </label>
    <Panel title="Audit records">
      {loading ? <LoadingState /> : error ? <ErrorState resource="audit records" detail={error} retry={reload} /> :
        data && <>{data.content.length ? <div className="table-scroll" tabIndex={0} role="region" aria-label="Audit table"><table className="audit-table">
          <thead><tr><th scope="col">Time</th><th scope="col">Actor</th><th scope="col">Action</th><th scope="col">Target</th><th scope="col">Details</th></tr></thead>
          <tbody>{data.content.map(record => <tr key={record.id}>
            <td className="date"><time dateTime={record.timestamp}>{formatTime(record.timestamp)}</time></td><td className="actor"><span className="truncate" title={record.actorEmail} tabIndex={0}>{record.actorEmail}</span></td>
            <td>{record.action.replaceAll('_', ' ')}</td><td className="target">{record.targetType.replaceAll('_', ' ')}<br /><code>{record.targetId}</code></td>
            <td className="audit-detail">{record.oldValue ?? 'None'} → {record.newValue ?? 'None'}</td>
          </tr>)}</tbody></table></div> : <EmptyState resource="audit records" />}
          <PaginationControls page={data} noun="audit records" onPage={setPage} /></>}
    </Panel>
  </>;
}
