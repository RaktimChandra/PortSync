import { useState } from 'react';
import { ApiRequestError, api } from '../api/client';
import type { Alert } from '../api/types';
import { AlertsTable } from '../components/AlertsTable';
import { ErrorNotice } from '../components/Notice';
import { useRefreshKey } from '../live/LiveContext';
import { useResource } from '../live/useResource';

type Filter = 'active' | 'all';

export function AlertsPage() {
  const [filter, setFilter] = useState<Filter>('active');
  const alerts = useResource(
    () => (filter === 'active' ? api.alerts(['OPEN', 'ACKNOWLEDGED']) : api.alerts()),
    `${filter}:${useRefreshKey('ALERTS')}`,
  );
  const [busyId, setBusyId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<Error | null>(null);

  async function act(alert: Alert, action: (id: number) => Promise<Alert>) {
    setBusyId(alert.id);
    setActionError(null);
    try {
      await action(alert.id);
      alerts.reload();
    } catch (e) {
      setActionError(e instanceof ApiRequestError ? e : (e as Error));
      alerts.reload();
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      <header className="page-head">
        <div>
          <h1>Alerts</h1>
          <p className="muted" style={{ marginTop: '0.4rem', maxWidth: '62ch' }}>
            Raised by SLA rules against simulated time. Acknowledge to show someone is on it. Alerts clear by themselves
            when the condition does; resolving one that is still breaching opens a new alert.
          </p>
        </div>
        <div className="segmented" role="group" aria-label="Which alerts">
          <button type="button" aria-pressed={filter === 'active'} onClick={() => setFilter('active')}>Active</button>
          <button type="button" aria-pressed={filter === 'all'} onClick={() => setFilter('all')}>All</button>
        </div>
      </header>
      <ErrorNotice error={alerts.error ?? actionError} />
      <div className="panel">
        {alerts.data && (
          <AlertsTable
            alerts={alerts.data}
            busyId={busyId}
            onAcknowledge={(a) => act(a, api.acknowledge)}
            onResolve={(a) => act(a, api.resolve)}
          />
        )}
      </div>
    </>
  );
}
