import type { ReactNode } from 'react';
import type { IncidentStatus, PageResponse } from '../types';
import { formatCount } from '../utils/format';

export function PageHeader({ title, description, onRefresh, loading }: {
  title: string; description: string; onRefresh: () => void; loading: boolean;
}) {
  return <header className="page-header"><div>
    <h1>{title}</h1><p className="subtle">{description}</p></div>
    <button onClick={onRefresh} disabled={loading} className="button secondary"><span aria-hidden="true">↻</span> Refresh</button>
  </header>;
}
export function SeverityBadge({ value }: { value: string }) {
  const tone = ({ HIGH: 'danger', MEDIUM: 'warning', LOW: 'neutral' } as Record<string, string>)[value.toUpperCase()] || 'neutral';
  return <span className={`badge severity ${tone}`}><span className="badge-dot" aria-hidden="true" />{value}</span>;
}
export function StatusBadge({ value }: { value: IncidentStatus }) {
  const tone = { OPEN: 'danger', ACKNOWLEDGED: 'warning', RESOLVED: 'success' }[value];
  return <span className={`badge ${tone}`}>{value.replaceAll('_', ' ')}</span>;
}
export function LoadingState() {
  return <div className="state" role="status"><span className="loading-mark" aria-hidden="true" /><strong>Loading workspace data…</strong><p>Retrieving the latest records.</p></div>;
}
export function ErrorState({ resource, detail, retry }: { resource: string; detail: string; retry: () => void }) {
  return <div className="state error" role="alert"><strong>Unable to load {resource}.</strong><p>{detail}</p><button className="button primary" onClick={retry}>Retry</button></div>;
}
export function EmptyState({ resource }: { resource: string }) {
  return <div className="state"><span className="empty-mark" aria-hidden="true">∅</span><strong>No {resource} found.</strong><p>There are no records to show. If filters are applied, try clearing them.</p></div>;
}
export function PaginationControls({ page, noun, onPage }: { page: PageResponse<unknown>; noun: string; onPage: (value: number) => void }) {
  return <div className="pagination"><span><span>Showing {page.content.length ? page.page * page.size + 1 : 0} to {page.content.length ? page.page * page.size + page.content.length : 0} of </span><strong>{formatCount(page.totalElements)}</strong> {noun}</span>
    <div><span>Page {page.totalPages === 0 ? 0 : page.page + 1} of {page.totalPages}</span>
      <button className="button secondary" disabled={page.first || page.page === 0} onClick={() => onPage(page.page - 1)}>Previous</button>
      <button className="button secondary" disabled={page.last || page.totalPages === 0} onClick={() => onPage(page.page + 1)}>Next</button>
    </div></div>;
}
export function Panel({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return <section className="panel" aria-label={title}><div className="panel-heading"><h2>{title}</h2>{action}</div>{children}</section>;
}
