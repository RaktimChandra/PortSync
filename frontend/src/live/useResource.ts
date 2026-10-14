import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiRequestError } from '../api/client';

export interface Resource<T> {
  data: T | null;
  error: ApiRequestError | Error | null;
  loading: boolean;
  reload: () => void;
}

/**
 * Loads data and reloads it whenever {@code refreshKey} changes (driven by the live stream).
 * A 30 s safety poll keeps the page roughly current even if the stream is down.
 */
export function useResource<T>(load: () => Promise<T>, refreshKey: string, pollMs = 30_000): Resource<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiRequestError | Error | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const loadRef = useRef(load);
  loadRef.current = load;

  const reload = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    let cancelled = false;
    loadRef.current()
      .then((value) => {
        if (!cancelled) {
          setData(value);
          setError(null);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e : new Error(String(e)));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [refreshKey, tick]);

  useEffect(() => {
    const id = setInterval(reload, pollMs);
    return () => clearInterval(id);
  }, [pollMs, reload]);

  return { data, error, loading, reload };
}
