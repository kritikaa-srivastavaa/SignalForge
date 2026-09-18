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
        data && <>{data.content.length ? <div className="table-wrap"><table>
          <thead><tr><th>Time</th><th>Actor</th><th>Action</th><th>Target</th><th>Details</th></tr></thead>
          <tbody>{data.content.map(record => <tr key={record.id}>
            <td>{formatTime(record.timestamp)}</td><td>{record.actorEmail}</td>
            <td>{record.action.replaceAll('_', ' ')}</td><td>{record.targetType.replaceAll('_', ' ')}<br /><code>{record.targetId}</code></td>
            <td>{record.oldValue ?? 'None'} → {record.newValue ?? 'None'}</td>
          </tr>)}</tbody></table></div> : <EmptyState resource="audit records" />}
          <PaginationControls page={data} noun="audit records" onPage={setPage} /></>}
    </Panel>
  </>;
}
