import type { Berth } from '../api/types';

/** The quay seen from above: one lane per berth, the hull fills as the vessel is worked. */
export function QuaySchematic({ berths }: { berths: Berth[] }) {
  return (
    <div className="quay panel">
      {berths.map((berth) => {
        const v = berth.vessel;
        const total = v ? v.dischargePlanned + v.loadPlanned : 0;
        const done = v ? v.discharged + v.loaded : 0;
        const progress = total > 0 ? done / total : 0;
        return (
          <div className="berth-lane" key={berth.id}>
            <div className="berth-id">{berth.id}</div>
            {v ? (
              <div>
                <div className="hull" title={`${v.name}: ${done} of ${total} moves`}>
                  <div className="hull-fill" style={{ width: `${progress * 100}%` }} />
                  <div className="hull-label">
                    <span>{v.name}</span>
                    <span className="code muted">{v.id}</span>
                  </div>
                </div>
                <div className="progress-line">
                  <span>Discharged {v.discharged}/{v.dischargePlanned}</span>
                  <span>Loaded {v.loaded}/{v.loadPlanned}</span>
                </div>
              </div>
            ) : (
              <div className="hull empty-berth" aria-label={`Berth ${berth.id} is free`}>
                <div className="hull-label muted">Free</div>
              </div>
            )}
            <div className="crane-row">
              {berth.cranes.length === 0 && <span className="muted">No cranes</span>}
              {berth.cranes.map((c) => (
                <span className="crane-chip" data-status={c.status} key={c.id}
                      title={c.status === 'FAULTED' ? `${c.faultCode ?? 'Fault'} since ${c.faultedSince ?? ''}` : `${c.movesLastHour} moves in the last hour`}>
                  {c.id}
                  <span className="muted">{c.status === 'FAULTED' ? 'down' : `${c.movesLastHour}/h`}</span>
                </span>
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}
