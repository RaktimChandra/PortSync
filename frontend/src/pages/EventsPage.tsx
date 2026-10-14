import { useState } from 'react';
import { api } from '../api/client';
import type { EventItem } from '../api/types';
import { ErrorNotice } from '../components/Notice';
import { OutcomePill } from '../components/Pills';
import { useLive, useRefreshKey } from '../live/LiveContext';
import { useResource } from '../live/useResource';
import { clock, dateTime, humanise } from '../lib/format';

type OutcomeFilter = 'all' | 'problems';

export function EventsPage() {
  const { feed, status } = useLive();
  const history = useResource(() => api.events(100), useRefreshKey());
  const dlq = useResource(() => api.dlq(100), useRefreshKey('DLQ'));
  const [filter, setFilter] = useState<OutcomeFilter>('all');

  // Live events first; before any arrive, show the latest stored ones.
  const merged: EventItem[] = feed.length > 0 ? feed : history.data ?? [];
  const shown = filter === 'all' ? merged : merged.filter((e) => e.outcome !== 'APPLIED');

  return (
    <>
      <header className="page-head">
        <h1>Event stream and dead letters</h1>
        <p>Every event as the core processes it, and every event it refused, with the reason.</p>
      </header>
      <ErrorNotice error={history.error ?? dlq.error} />
      <div className="two-col">
        <section aria-labelledby="stream-heading">
          <div className="page-head" style={{ marginBottom: '0.85rem' }}>
            <h2 id="stream-heading">Live events</h2>
            <div className="segmented" role="group" aria-label="Filter events">
              <button type="button" aria-pressed={filter === 'all'} onClick={() => setFilter('all')}>All</button>
              <button type="button" aria-pressed={filter === 'problems'} onClick={() => setFilter('problems')}>Not applied</button>
            </div>
          </div>
          <div className="panel">
            {status !== 'open' && <p className="notice">Live stream {status}. Showing stored events.</p>}
            {shown.length === 0 ? (
              <p className="empty">Nothing to show yet.</p>
            ) : (
              <ul className="feed" aria-live="off">
                {shown.slice(0, 150).map((e) => (
                  <li key={`${e.eventId}-${e.offset}-${e.outcome}`}>
                    <span className="muted">{clock(e.occurredAt)}</span>
                    <span>{e.type}</span>
                    <span className="code">{e.entityId}</span>
                    <OutcomePill outcome={e.outcome} />
                    {e.outcome !== 'APPLIED' && e.detail && <span className="detail">{e.detail}</span>}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>
        <section aria-labelledby="dlq-heading">
          <h2 id="dlq-heading" style={{ marginBottom: '0.85rem' }}>Dead-letter queue</h2>
          <div className="panel">
            {dlq.data?.length === 0 && <p className="empty">Nothing has been rejected.</p>}
            {dlq.data && dlq.data.length > 0 && (
              <ul className="feed">
                {dlq.data.map((d) => (
                  <li key={d.id}>
                    <span className="muted">{dateTime(d.deadLetteredAt).slice(6)}</span>
                    <span className="tone-crit">{humanise(d.reason)}</span>
                    <span className="code">{d.entityId ?? '–'}</span>
                    <span className="muted">{d.type ?? 'unreadable'}</span>
                    <span className="detail">
                      {d.detail}
                      <details className="raw">
                        <summary>Original record ({d.sourceTopic} partition {d.sourcePartition}, offset {d.sourceOffset})</summary>
                        <pre>{d.rawValue}</pre>
                      </details>
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>
      </div>
    </>
  );
}
