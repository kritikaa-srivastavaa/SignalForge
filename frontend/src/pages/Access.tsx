import { useCallback, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { getCurrentUser } from '../api/auth';
import { latestRequest, requestAccess } from '../api/governance';
import { ApiError } from '../api/client';
import { useRemote } from '../hooks/useRemote';
import { ErrorState, LoadingState, PageHeader, Panel } from '../components/Shared';
import { formatTime } from '../utils/format';

export function Access() {
  const { user, updateCurrentUser } = useAuth();
  const load = useCallback(async (signal: AbortSignal) => {
    const [request, current] = await Promise.all([latestRequest(signal), getCurrentUser(signal)]);
    return { request, current };
  }, []);
  const { data, loading, error, reload } = useRemote(load);
  const [pending, setPending] = useState(false);
  const [feedback, setFeedback] = useState('');
  const submitting = useRef(false);
  // Fetching status refreshes the same session identity; no optimistic role grant.
  const current = data?.current ?? user;
  async function submit() {
    if (submitting.current) return;
    submitting.current = true; setPending(true); setFeedback('');
    try { await requestAccess(); reload(); }
    catch (failure) {
      setFeedback(failure instanceof ApiError && failure.status === 409
        ? 'A request is already pending or you already have access. The latest state has been refreshed.'
        : 'Unable to request access. Please retry.');
      if (failure instanceof ApiError && failure.status === 409) reload();
    } finally { submitting.current = false; setPending(false); }
  }
  // Applying the response outside rendering keeps navigation consistent with /auth/me.
  const syncRole = () => { if (data?.current) updateCurrentUser(data.current); };
  return <>
    <PageHeader title="My access" description="OPERATOR access permits acknowledging and resolving incidents."
      loading={loading} onRefresh={reload} />
    <Panel title="Operational access">
      {loading ? <LoadingState /> : error ? <ErrorState resource="access request" detail={error} retry={reload} /> :
        <AccessState currentRole={current?.role} data={data} syncRole={syncRole} pending={pending} submit={submit} />}
      {feedback && <p role="alert">{feedback}</p>}
    </Panel>
  </>;
}

import { useEffect } from 'react';
import type { User } from '../types';
import type { AccessRequest } from '../api/governance';
function AccessState({ currentRole, data, syncRole, pending, submit }: {
  currentRole?: string; data?: { request: AccessRequest | undefined; current: User };
  syncRole: () => void; pending: boolean; submit: () => void;
}) {
  useEffect(() => { syncRole(); }, [data]); // Sync only after a fresh server response.
  const request = data?.request;
  return <div className="governance-content">
    {currentRole !== 'VIEWER' ? <p role="status">You have operational access ({currentRole}).</p> :
      <p>Read-only access: you can view events and incidents. Request OPERATOR access to manage incident lifecycles.</p>}
    {request && <div role="status">
      <strong>{request.status === 'PENDING' ? 'Access request pending' : request.status === 'APPROVED' ? 'Access request approved' : 'Access request rejected'}</strong>
      <p>Requested {formatTime(request.createdAt)}{request.reviewedAt && ` · Reviewed ${formatTime(request.reviewedAt)}`}</p>
      {request.reviewReason && <p>{request.reviewReason}</p>}
    </div>}
    {currentRole === 'VIEWER' && request?.status !== 'PENDING' &&
      <button className="button primary" onClick={submit} disabled={pending}>{pending ? 'Requesting...' : 'Request Operator Access'}</button>}
    {currentRole === 'VIEWER' && request?.status === 'APPROVED' &&
      <p>Your current role is VIEWER. A historical approval does not override a later administrative role change.</p>}
    <p className="subtle">Use Refresh to check a review decision and update your session role without logging in again.</p>
  </div>;
}
