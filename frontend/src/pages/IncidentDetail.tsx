import { useAuth } from '../auth/AuthProvider';
import { canManageIncidents } from '../auth/permissions';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { acknowledgeIncident, getIncident, resolveIncident } from '../api/incidents';
import { ErrorState, LoadingState, Panel, SeverityBadge, StatusBadge } from '../components/Shared';
import type { Incident } from '../types';
import { formatTime, timeZone } from '../utils/format';

type Action = 'acknowledge' | 'resolve';
type Feedback = { tone: 'success' | 'warning' | 'error'; message: string };

function CopyId({ label, value }: { label: string; value: string }) {
  const [feedback, setFeedback] = useState('');
  async function copy() {
    try {
      if (!navigator.clipboard) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(value);
      setFeedback('Copied.');
    } catch {
      setFeedback('Copy unavailable. Select the ID and copy it manually.');
    }
  }
  return <div className="detail-id">
    <dt>{label}</dt>
    <dd><code>{value}</code><button className="button secondary" onClick={copy} aria-label={`Copy ${label.toLowerCase()}`}>Copy</button></dd>
    {feedback && <p className="subtle" role="status">{feedback}</p>}
  </div>;
}

export function IncidentDetail() {
  const { id = '' } = useParams();
  // Route changes must not retain another incident's data or pending action.
  return <IncidentDetailContent key={id} id={id} />;
}

function IncidentDetailContent({ id }: { id: string }) {
  const { user } = useAuth();
  const [incident, setIncident] = useState<Incident>();
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string>();
  const [notFound, setNotFound] = useState(false);
  const [pending, setPending] = useState<Action | null>(null);
  const [confirmResolve, setConfirmResolve] = useState(false);
  const [feedback, setFeedback] = useState<Feedback>();
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const request = useRef<AbortController | null>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const resolveButton = useRef<HTMLButtonElement>(null);

  const load = useCallback(async () => {
    if (request.current) return;
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    setLoadError(undefined);
    setNotFound(false);
    setFeedback(undefined);
    setConfirmResolve(false);
    try {
      const latest = await getIncident(id, controller.signal);
      if (!controller.signal.aborted) {
        setIncident(latest);
        setNeedsRefresh(false);
      }
    } catch (error: unknown) {
      if (!controller.signal.aborted) {
        if (error instanceof ApiError && error.status === 404) setNotFound(true);
        else setLoadError(error instanceof ApiError ? error.message : 'Check that the backend is running and reachable.');
      }
    } finally {
      if (!controller.signal.aborted) {
        request.current = null;
        setLoading(false);
      }
    }
  }, [id]);

  useEffect(() => {
    void load();
    return () => {
      request.current?.abort();
      request.current = null;
    };
  }, [load]);

  useEffect(() => {
    if (confirmResolve) cancelButton.current?.focus();
  }, [confirmResolve]);

  async function mutate(action: Action) {
    // The ref closes the gap before React renders disabled buttons.
    if (!canManageIncidents(user) || request.current || !incident || needsRefresh) return;
    if (incident.status === 'RESOLVED' || (action === 'acknowledge' && incident.status !== 'OPEN')) return;
    if (action === 'resolve' && !confirmResolve) return;
    const controller = new AbortController();
    request.current = controller;
    setPending(action);
    setFeedback(undefined);
    try {
      const saved = await (action === 'acknowledge' ? acknowledgeIncident : resolveIncident)(id, controller.signal);
      if (!controller.signal.aborted) {
        setIncident(saved);
        setConfirmResolve(false);
        setFeedback({ tone: 'success', message: action === 'acknowledge' ? 'Incident acknowledged.' : 'Incident resolved.' });
      }
    } catch (error: unknown) {
      if (controller.signal.aborted) return;
      setConfirmResolve(false);
      if (error instanceof ApiError && error.status === 404) {
        setNotFound(true);
      } else if (error instanceof ApiError && error.status === 409) {
        setFeedback({ tone: 'warning', message: 'This incident changed while you were viewing it. Loading the latest state...' });
        try {
          const latest = await getIncident(id, controller.signal);
          if (!controller.signal.aborted) {
            setIncident(latest);
            setFeedback({ tone: 'warning', message: 'This incident changed while you were viewing it. The latest state has been loaded.' });
          }
        } catch (refreshError: unknown) {
          if (controller.signal.aborted) return;
          if (refreshError instanceof ApiError && refreshError.status === 404) setNotFound(true);
          else {
            setNeedsRefresh(true);
            setFeedback({ tone: 'warning', message: 'This incident changed, but its latest state could not be loaded. Refresh before trying another action.' });
          }
        }
      } else {
        setFeedback({ tone: 'error', message: `Unable to ${action} this incident. Refresh to check its state or try again.` });
      }
    } finally {
      if (!controller.signal.aborted) {
        request.current = null;
        setPending(null);
      }
    }
  }

  function cancelResolve() {
    setConfirmResolve(false);
    resolveButton.current?.focus();
  }

  return <>
    <Link className="text-link detail-back" to="/incidents">← Back to incidents</Link>
    {loading ? <LoadingState /> : notFound ? <section className="state" aria-label="Incident not found">
      <h1>Incident not found</h1><p>The incident may no longer exist or the link may be invalid.</p>
    </section> : loadError ? <ErrorState resource="incident" detail={loadError} retry={() => void load()} /> : incident && <>
      <header className="page-header detail-heading">
        <div><div className="detail-status"><SeverityBadge value={incident.severity} /><StatusBadge value={incident.status} /></div><h1>{incident.title}</h1><p className="detail-context"><code>{incident.service}</code> / <code>{incident.type}</code></p></div>
        <button className="button secondary" disabled={pending !== null} onClick={() => void load()}>Refresh</button>
      </header>
      <Panel title="Incident details" action={<span className="subtle">Times shown in {timeZone}</span>}>
        <dl className="detail-fields">
          <div><dt>Service</dt><dd>{incident.service}</dd></div>
          <div><dt>Type</dt><dd>{incident.type}</dd></div>
          <div><dt>Severity</dt><dd><SeverityBadge value={incident.severity} /></dd></div>
          <div><dt>Created At</dt><dd><time dateTime={incident.createdAt}>{formatTime(incident.createdAt)}</time></dd></div>
          <CopyId label="Incident ID" value={incident.id} />
          <CopyId label="Source Event ID" value={incident.sourceEventId} />
        </dl>
      </Panel>
      <Panel title="Incident Actions">
        <div className="incident-actions" aria-busy={pending !== null}>
          {feedback && <p className={`action-feedback ${feedback.tone}`} role={feedback.tone === 'error' ? 'alert' : 'status'}>{feedback.message}</p>}
          {!canManageIncidents(user) ? <p className="subtle">Read-only access. An operator or administrator can manage incidents.</p> : incident.status === 'RESOLVED' ? <p className="subtle">This incident is resolved. No further lifecycle actions are available.</p> : <>
            <p className="subtle">{incident.status === 'OPEN' ? 'Acknowledge to mark this incident as under investigation, or resolve it when work is complete.' : 'This incident is acknowledged. Resolve it when work is complete.'}</p>
            <div className="detail-buttons">
              {incident.status === 'OPEN' && <button className="button secondary" disabled={pending !== null || needsRefresh} onClick={() => void mutate('acknowledge')}>{pending === 'acknowledge' ? 'Acknowledging...' : 'Acknowledge'}</button>}
              <button ref={resolveButton} className="button primary" disabled={pending !== null || needsRefresh} onClick={() => setConfirmResolve(true)}>{pending === 'resolve' ? 'Resolving...' : 'Resolve'}</button>
            </div>
            {confirmResolve && <section className="resolve-confirmation" aria-labelledby="resolve-title" onKeyDown={event => { if (event.key === 'Escape' && !pending) cancelResolve(); }}>
              <h3 id="resolve-title">Resolve this incident?</h3>
              <p>This marks the incident as resolved. It cannot be reopened through this console.</p>
              <div className="detail-buttons">
                <button ref={cancelButton} className="button secondary" disabled={pending !== null} onClick={cancelResolve}>Cancel</button>
                <button className="button primary" disabled={pending !== null} onClick={() => void mutate('resolve')}>{pending === 'resolve' ? 'Resolving...' : 'Confirm resolve'}</button>
              </div>
            </section>}
          </>}
        </div>
      </Panel>
    </>}
  </>;
}