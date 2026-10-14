import { humanise } from '../lib/format';

const outcomeTone: Record<string, string> = {
  APPLIED: 'tone-ok',
  IGNORED_STALE: 'tone-stale',
  REJECTED: 'tone-crit',
  DUPLICATE: 'tone-muted',
};

export function OutcomePill({ outcome }: { outcome: string }) {
  const label = outcome === 'IGNORED_STALE' ? 'Stale' : humanise(outcome);
  return <span className={`pill ${outcomeTone[outcome] ?? 'tone-muted'}`}>{label}</span>;
}

const stateTone: Record<string, string> = {
  OPEN: 'tone-crit',
  ACKNOWLEDGED: 'tone-warn',
  RESOLVED: 'tone-muted',
};

export function AlertStatePill({ state }: { state: string }) {
  return <span className={`pill ${stateTone[state] ?? 'tone-muted'}`}>{humanise(state)}</span>;
}

export function SeverityPill({ severity }: { severity: string }) {
  return <span className={`pill ${severity === 'CRITICAL' ? 'tone-crit' : 'tone-warn'}`}>{humanise(severity)}</span>;
}
