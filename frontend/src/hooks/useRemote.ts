import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';

export function useRemote<T>(load: (signal: AbortSignal) => Promise<T>) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setData(undefined);
    setError(undefined);
    load(controller.signal).then(result => {
      if (!controller.signal.aborted) setData(result);
    }).catch((failure: unknown) => {
      if (!controller.signal.aborted) {
        setError(failure instanceof ApiError ? failure.message : 'Check that the backend is running and reachable.');
      }
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [load, revision]);
  return { data, error, loading, reload: () => setRevision(value => value + 1) };
}
