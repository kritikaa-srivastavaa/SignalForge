import { useCallback, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { canManageUsers } from '../auth/permissions';
import { Forbidden } from '../components/Forbidden';
import { EmptyState, ErrorState, LoadingState, PageHeader, PaginationControls, Panel } from '../components/Shared';
import { approveRequest, getRequests, rejectRequest, getGroupRequests, approveGroupRequest, rejectGroupRequest } from '../api/governance';
import type { AccessRequest, AccessStatus } from '../api/governance';
import { ApiError } from '../api/client';
import { useRemote } from '../hooks/useRemote';
import { formatTime } from '../utils/format';

export function AdminAccessRequests() {
  const { user } = useAuth();
  return canManageUsers(user) ? <RequestQueue /> : <Forbidden />;
}
export function RequestQueue({ group = false }: { group?: boolean }) {
  const { user } = useAuth();
  const [status, setStatus] = useState<AccessStatus | ''>('PENDING');
  const [page, setPage] = useState(0);
  const [feedback, setFeedback] = useState('');
  const load = useCallback((signal: AbortSignal) => group ? getGroupRequests(page, signal) : getRequests(status, page, signal), [group, status, page]);
  const { data, loading, error, reload } = useRemote(load);
  function reviewed(message: string) { setFeedback(message); reload(); }
  return <>
    <PageHeader title="Access requests" description={group ? `Review pending requests for ${user?.role} access.` : "Review requests for VIEWER and OPERATOR access."} loading={loading} onRefresh={reload} />
    {!group && <label className="governance-filter">Request status
      <select value={status} onChange={event => { setStatus(event.target.value as AccessStatus | ''); setPage(0); }}>
        <option value="">All statuses</option>{(['PENDING', 'APPROVED', 'REJECTED'] as const).map(value => <option key={value}>{value}</option>)}
      </select>
    </label>}
    {feedback && <p role="status">{feedback}</p>}
    <Panel title="Request queue">
      {loading ? <LoadingState /> : error ? <ErrorState resource="access requests" detail={error} retry={reload} /> :
        data && <>{data.content.length ? <div className="request-list"><div className="admin-user admin-list-header" aria-hidden="true"><span>Requester</span><span>Access / status</span><span>Requested</span><span>Review</span></div>{data.content.map(request => <RequestRow key={request.id} group={group} request={request} reviewed={reviewed} />)}</div>
          : <EmptyState resource="access requests" />}
          <PaginationControls page={data} noun="requests" onPage={setPage} /></>}
    </Panel>
  </>;
}
function RequestRow({ request, reviewed, group }: { group: boolean; request: AccessRequest; reviewed: (message: string) => void }) {
  const { user } = useAuth();
  const [pending, setPending] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const submitting = useRef(false);
  async function review(approve: boolean) {
    if (submitting.current) return;
    submitting.current = true; setPending(true); setError('');
    try {
      const saved = approve
        ? await (group ? approveGroupRequest : approveRequest)(request.id)
        : await (group ? rejectGroupRequest : rejectRequest)(request.id, reason);
      reviewed(`Request for ${saved.requesterEmail}: ${saved.status}.`);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 409) {
        reviewed('This request was already reviewed. The queue has been refreshed; your action was not applied.');
      } else { setError(failure instanceof ApiError ? failure.message : 'Unable to review request. Please retry.'); }
    } finally { submitting.current = false; setPending(false); }
  }
  return <section className={'admin-user' + (request.status === 'PENDING' ? '' : ' history')} aria-label={request.requesterEmail} aria-busy={pending}>
    <div><strong>{request.requesterEmail}</strong></div><div><span className="subtle">{request.requestedRole}</span><p><span className={"badge " + (request.status === "PENDING" ? "warning" : "neutral")}>{request.status}</span></p></div><div className="date">Requested {formatTime(request.createdAt)}
      {request.reviewedAt && <p>Reviewed {formatTime(request.reviewedAt)} by <code>{request.reviewedBy}</code></p>}
      {request.reviewReason && <p>{request.reviewReason}</p>}</div>
    {request.status === 'PENDING' && (request.requesterId === user?.id ? <p>{group ? 'Another eligible group member must review your request.' : 'Another administrator must review your request.'}</p> :
      <div className="request-actions">{rejecting ? <form onSubmit={event => { event.preventDefault(); void review(false); }}>
        <label htmlFor={'reason-' + request.id}>Reason (optional, visible to requester)</label>
        <input id={'reason-' + request.id} value={reason} maxLength={300} disabled={pending} onChange={event => setReason(event.target.value)} />
        <button className="button primary" disabled={pending} type="submit">{pending ? 'Saving...' : 'Confirm rejection'}</button>
        <button className="button secondary" disabled={pending} onClick={() => setRejecting(false)} type="button">Cancel</button>
      </form> : <div className="detail-buttons">
        <button className="button primary" disabled={pending} onClick={() => void review(true)}>{pending ? 'Saving...' : 'Approve'}</button>
        <button className="button secondary" disabled={pending} onClick={() => setRejecting(true)}>Reject</button>
      </div>}{error && <p role="alert">{error}</p>}</div>)}
  </section>;
}
