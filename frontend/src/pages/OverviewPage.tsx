import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { AlertsTable } from '../components/AlertsTable';
import { KpiBand } from '../components/KpiBand';
import { Meter } from '../components/Meter';
import { ErrorNotice } from '../components/Notice';
import { OutcomePill } from '../components/Pills';
import { QuaySchematic } from '../components/QuaySchematic';
import { useLive, useRefreshKey } from '../live/LiveContext';
import { useResource } from '../live/useResource';
import { clock, percent } from '../lib/format';

export function OverviewPage() {
  const overview = useResource(api.overview, useRefreshKey('BERTHS', 'CRANES', 'YARD', 'GATE', 'VESSELS', 'ALERTS', 'DLQ'), 10_000);
  const berths = useResource(api.berths, useRefreshKey('BERTHS', 'CRANES', 'VESSELS'));
  const alerts = useResource(() => api.alerts(['OPEN', 'ACKNOWLEDGED']), useRefreshKey('ALERTS'));
  const { feed } = useLive();

  return (
    <>
      <header className="page-head">
        <h1>Terminal overview</h1>
        <p>Live picture of the quay, the yard and the gate. Figures update as events are processed.</p>
      </header>
      <ErrorNotice error={overview.error} />
      {overview.data && !overview.data.clockSynchronised && (
        <p className="notice">No events received yet. Start the simulator to bring the terminal to life.</p>
      )}
      {overview.data && <KpiBand overview={overview.data} />}

      <section className="section" aria-labelledby="quay-heading">
        <h2 id="quay-heading">Quay</h2>
        {berths.data && <QuaySchematic berths={berths.data} />}
      </section>

      <section className="section" aria-labelledby="yard-heading">
        <h2 id="yard-heading">Yard blocks</h2>
        <div className="block-bars">
          {overview.data?.blocks.map((b) => (
            <Link to={`/yard?block=${b.block}`} key={b.block} className="panel block-bar" style={{ color: 'inherit', textDecoration: 'none' }}>
              <div className="block-bar-head">
                <strong>{b.block}</strong>
                <span>{percent(b.utilisation)} <span className="muted">({b.used}/{b.capacity})</span></span>
              </div>
              <Meter ratio={b.utilisation} limit={0.85} label={`Block ${b.block} utilisation`} />
            </Link>
          ))}
        </div>
      </section>

      <div className="two-col section">
        <section aria-labelledby="alerts-heading">
          <h2 id="alerts-heading">Active alerts</h2>
          <div className="panel">{alerts.data && <AlertsTable alerts={alerts.data.slice(0, 6)} />}</div>
        </section>
        <section aria-labelledby="latest-heading">
          <h2 id="latest-heading">Latest events</h2>
          <div className="panel">
            {feed.length === 0 ? (
              <p className="empty">Waiting for the first live event.</p>
            ) : (
              <ul className="feed">
                {feed.slice(0, 10).map((e) => (
                  <li key={`${e.eventId}-${e.offset}-${e.outcome}`}>
                    <span className="muted">{clock(e.occurredAt)}</span>
                    <span>{e.type}</span>
                    <span className="code">{e.entityId}</span>
                    <OutcomePill outcome={e.outcome} />
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
