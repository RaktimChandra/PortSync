import { api } from '../api/client';
import { ErrorNotice } from '../components/Notice';
import { QuaySchematic } from '../components/QuaySchematic';
import { useRefreshKey } from '../live/LiveContext';
import { useResource } from '../live/useResource';
import { clock, dateTime, humanise } from '../lib/format';

export function BerthsPage() {
  const berths = useResource(api.berths, useRefreshKey('BERTHS', 'CRANES', 'VESSELS'));
  const cranes = useResource(api.cranes, useRefreshKey('CRANES'));
  const waiting = useResource(() => api.vessels('WAITING'), useRefreshKey('VESSELS'));
  const trucks = useResource(api.truckVisits, useRefreshKey('GATE'));

  return (
    <>
      <header className="page-head">
        <h1>Berths and cranes</h1>
        <p>Which vessel is at each berth, how far its discharge and load have got, and the state of every quay crane.</p>
      </header>
      <ErrorNotice error={berths.error ?? cranes.error} />
      {berths.data && <QuaySchematic berths={berths.data} />}

      <section className="section" aria-labelledby="cranes-heading">
        <h2 id="cranes-heading">Quay cranes</h2>
        <div className="panel table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>Crane</th>
                <th>Status</th>
                <th>Berth</th>
                <th className="num">Moves, last hour</th>
                <th>Fault</th>
                <th className="num">Last heartbeat</th>
              </tr>
            </thead>
            <tbody>
              {cranes.data?.map((c) => (
                <tr key={c.id}>
                  <td className="code">{c.id}</td>
                  <td>
                    <span className={`pill ${c.status === 'FAULTED' ? 'tone-crit' : c.status === 'WORKING' ? 'tone-ok' : 'tone-muted'}`}>
                      {humanise(c.status)}
                    </span>
                  </td>
                  <td>{c.berthId ?? '–'}</td>
                  <td className="num">{c.movesLastHour}</td>
                  <td>{c.faultCode ? `${humanise(c.faultCode)} since ${clock(c.faultedSince)}` : '–'}</td>
                  <td className="num">{clock(c.lastHeartbeatAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <div className="two-col section">
        <section aria-labelledby="queue-heading">
          <h2 id="queue-heading">Waiting for a berth</h2>
          <div className="panel">
            {waiting.data?.length === 0 && <p className="empty">No vessel is waiting.</p>}
            {waiting.data && waiting.data.length > 0 && (
              <table className="data">
                <thead>
                  <tr><th>Vessel</th><th>Voyage</th><th className="num">Arrived</th><th className="num">Moves planned</th></tr>
                </thead>
                <tbody>
                  {waiting.data.map((v) => (
                    <tr key={v.id}>
                      <td>{v.name} <span className="code muted">{v.id}</span></td>
                      <td className="code">{v.voyage}</td>
                      <td className="num">{dateTime(v.arrivedAt)}</td>
                      <td className="num">{v.dischargePlanned + v.loadPlanned}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </section>
        <section aria-labelledby="gate-heading">
          <h2 id="gate-heading">Trucks inside the terminal</h2>
          <div className="panel table-wrap">
            {trucks.data?.length === 0 && <p className="empty">No trucks inside.</p>}
            {trucks.data && trucks.data.length > 0 && (
              <table className="data">
                <thead>
                  <tr><th>Visit</th><th>Purpose</th><th>Container</th><th className="num">Minutes inside</th></tr>
                </thead>
                <tbody>
                  {trucks.data.slice(0, 15).map((t) => (
                    <tr key={t.id}>
                      <td className="code">{t.id}</td>
                      <td>{t.direction === 'PICK_UP' ? 'Collect import' : 'Deliver export'}</td>
                      <td className="code">{t.containerNumber}</td>
                      <td className={`num ${t.minutesInside > 45 ? 'tone-warn' : ''}`}>{t.minutesInside}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </section>
      </div>
    </>
  );
}
