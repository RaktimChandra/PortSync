import type { Overview } from '../api/types';
import { percent } from '../lib/format';

/** The five headline numbers, as one band rather than a grid of cards. */
export function KpiBand({ overview }: { overview: Overview }) {
  const turn = overview.avgTruckTurnMinutes;
  const utilisationTone = overview.blocks.some((b) => b.utilisation > 0.85) ? 'warn' : undefined;
  return (
    <section className="kpis" aria-label="Key figures">
      <div className="kpi">
        <div className="kpi-value">{overview.vesselsAtBerth}<small>/ 3</small></div>
        <div className="kpi-label">Vessels at berth</div>
        <div className="kpi-sub muted">{overview.vesselsWaiting} waiting</div>
      </div>
      <div className="kpi">
        <div className="kpi-value">{overview.craneMovesLastHour}</div>
        <div className="kpi-label">Crane moves, last hour</div>
        <div className={`kpi-sub ${overview.cranesFaulted > 0 ? 'tone-crit' : 'muted'}`}>
          {overview.cranesFaulted > 0 ? `${overview.cranesFaulted} crane${overview.cranesFaulted > 1 ? 's' : ''} down` : 'All cranes up'}
        </div>
      </div>
      <div className="kpi" data-tone={utilisationTone}>
        <div className="kpi-value">{percent(overview.yardUtilisation)}</div>
        <div className="kpi-label">Yard utilisation</div>
        <div className="kpi-sub muted">
          Fullest block {[...overview.blocks].sort((a, b) => b.utilisation - a.utilisation)[0]?.block ?? '–'}
        </div>
      </div>
      <div className="kpi" data-tone={overview.openAlerts > 0 ? 'crit' : undefined}>
        <div className="kpi-value">{overview.openAlerts}</div>
        <div className="kpi-label">Open alerts</div>
        <div className="kpi-sub muted">{overview.acknowledgedAlerts} acknowledged</div>
      </div>
      <div className="kpi" data-tone={turn !== null && turn > 45 ? 'warn' : undefined}>
        <div className="kpi-value">
          {turn === null ? '–' : Math.round(turn)}
          {turn !== null && <small>min</small>}
        </div>
        <div className="kpi-label">Truck turn time, last hour</div>
        <div className="kpi-sub muted">{overview.trucksInTerminal} trucks inside</div>
      </div>
    </section>
  );
}
