import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { EventItem } from '../api/types';
import { EventStream, type StreamMessage, type StreamStatus } from './eventStream';

/** Parts of the dashboard that can go stale; the server tells us which ones changed. */
export type Scope = 'BERTHS' | 'CRANES' | 'YARD' | 'GATE' | 'CONTAINERS' | 'VESSELS' | 'ALERTS' | 'DLQ';

interface LiveState {
  status: StreamStatus;
  /** Most recent processed events, newest first (from the stream only). */
  feed: EventItem[];
  /** Bumped every time a scope changes; use it as a dependency to re-fetch. */
  versions: Record<Scope, number>;
  /** Bumped on every (re)connect so pages reload everything they show. */
  generation: number;
}

const initialVersions: Record<Scope, number> = {
  BERTHS: 0, CRANES: 0, YARD: 0, GATE: 0, CONTAINERS: 0, VESSELS: 0, ALERTS: 0, DLQ: 0,
};

const LiveContext = createContext<LiveState>({ status: 'connecting', feed: [], versions: initialVersions, generation: 0 });

const FEED_SIZE = 200;
/** Coalesce bursts: at most one refresh signal per scope per interval. */
const THROTTLE_MS = 1500;

export function LiveProvider({ children, url = '/api/stream' }: { children: ReactNode; url?: string }) {
  const [status, setStatus] = useState<StreamStatus>('connecting');
  const [feed, setFeed] = useState<EventItem[]>([]);
  const [versions, setVersions] = useState(initialVersions);
  const [generation, setGeneration] = useState(0);
  const pending = useRef(new Set<Scope>());
  const flushTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const touch = (scopes: Scope[]) => {
      scopes.forEach((s) => pending.current.add(s));
      if (flushTimer.current) return;
      flushTimer.current = setTimeout(() => {
        const changed = Array.from(pending.current);
        pending.current.clear();
        flushTimer.current = null;
        setVersions((v) => {
          const next = { ...v };
          changed.forEach((s) => (next[s] = v[s] + 1));
          return next;
        });
      }, THROTTLE_MS);
    };

    const onMessage = ({ name, data }: StreamMessage) => {
      if (name === 'event') {
        setFeed((f) => [data as EventItem, ...f].slice(0, FEED_SIZE));
      } else if (name === 'state') {
        touch(((data as { scopes?: Scope[] }).scopes ?? []) as Scope[]);
      } else if (name === 'alert') {
        touch(['ALERTS']);
      } else if (name === 'dlq') {
        touch(['DLQ']);
      }
    };

    const stream = new EventStream({
      url,
      names: ['hello', 'event', 'state', 'alert', 'dlq'],
      onMessage,
      onStatus: (s) => {
        setStatus(s);
        if (s === 'open') setGeneration((g) => g + 1);
      },
    });
    stream.start();
    return () => {
      stream.stop();
      if (flushTimer.current) clearTimeout(flushTimer.current);
    };
  }, [url]);

  const value = useMemo(() => ({ status, feed, versions, generation }), [status, feed, versions, generation]);
  return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>;
}

export function useLive(): LiveState {
  return useContext(LiveContext);
}

/** A number that changes whenever any of the given scopes changes or the stream reconnects. */
export function useRefreshKey(...scopes: Scope[]): string {
  const { versions, generation } = useLive();
  return `${generation}:${scopes.map((s) => versions[s]).join(',')}`;
}
