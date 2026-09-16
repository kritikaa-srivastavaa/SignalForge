import { useCallback, useState } from 'react';
import type { ReactNode } from 'react';
import { Filters, emptyFilters } from '../components/Filters';
import { EmptyState, ErrorState, LoadingState, PageHeader, PaginationControls, Panel } from '../components/Shared';
import { useRemote } from '../hooks/useRemote';
import type { Filters as FilterValues, IncidentQuery, PageResponse } from '../types';
import { timeZone } from '../utils/format';

export function CollectionPage<T>({ title, description, load, table }: {
  title: 'Events' | 'Incidents'; description: string;
  load: (query: IncidentQuery, signal?: AbortSignal) => Promise<PageResponse<T>>;
  table: (rows: T[]) => ReactNode;
}) {
  const [query, setQuery] = useState<IncidentQuery>({ page: 0, size: 20, ...emptyFilters });
  const fetchPage = useCallback((signal: AbortSignal) => load(query, signal), [load, query]);
  const { data, error, loading, reload } = useRemote(fetchPage);
  const apply = (filters: FilterValues) => setQuery({ ...filters, page: 0, size: query.size });
  return <>
    <PageHeader title={title} description={description} onRefresh={reload} loading={loading} />
    <Filters incidents={title === 'Incidents'} onApply={apply} />
    <Panel title={`All ${title.toLowerCase()}`} action={<span className="subtle table-note">Newest first · {timeZone}</span>}>
      {loading ? <LoadingState /> : error ? <ErrorState resource={title.toLowerCase()} detail={error} retry={reload} /> : data && <>
        {data.content.length ? table(data.content) : <EmptyState resource={title.toLowerCase()} />}
        <PaginationControls page={data} noun={title.toLowerCase()} onPage={page => setQuery({ ...query, page })} />
      </>}
    </Panel>
  </>;
}
