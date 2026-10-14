import type { Alert } from '../api/types';
import { dateTime, ruleName } from '../lib/format';
import { AlertStatePill, SeverityPill } from './Pills';

interface AlertsTableProps {
  alerts: Alert[];
  busyId?: number | null;
  onAcknowledge?: (alert: Alert) => void;
  onResolve?: (alert: Alert) => void;
}

export function AlertsTable({ alerts, busyId, onAcknowledge, onResolve }: AlertsTableProps) {
  if (alerts.length === 0) {
    return <p className="empty">No alerts. Every SLA rule is within its limit.</p>;
  }
  return (
    <div className="table-wrap">
      <table className="data">
        <thead>
          <tr>
            <th>Severity</th>
            <th>Rule</th>
            <th>Entity</th>
            <th>What is happening</th>
            <th>Opened</th>
            <th>State</th>
            {(onAcknowledge || onResolve) && <th><span className="sr-only">Actions</span></th>}
          </tr>
        </thead>
        <tbody>
          {alerts.map((a) => (
            <tr key={a.id}>
              <td><SeverityPill severity={a.severity} /></td>
              <td>{ruleName(a.rule)}</td>
              <td className="code">{a.entityId}</td>
              <td>{a.message}</td>
              <td className="num">{dateTime(a.openedAt)}</td>
              <td>
                <AlertStatePill state={a.state} />
                {a.resolution && <div className="muted">{a.resolution === 'AUTO' ? 'Cleared by itself' : 'Resolved by operator'}</div>}
              </td>
              {(onAcknowledge || onResolve) && (
                <td>
                  <div className="actions">
                    {onAcknowledge && (
                      <button className="btn" type="button" disabled={a.state !== 'OPEN' || busyId === a.id}
                              onClick={() => onAcknowledge(a)} aria-label={`Acknowledge alert ${a.id}`}>
                        Acknowledge
                      </button>
                    )}
                    {onResolve && (
                      <button className="btn" type="button" disabled={a.state === 'RESOLVED' || busyId === a.id}
                              onClick={() => onResolve(a)} aria-label={`Resolve alert ${a.id}`}>
                        Resolve
                      </button>
                    )}
                  </div>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
