interface MeterProps {
  ratio: number;
  limit?: number;
  critical?: number;
  label: string;
}

/** Horizontal fill bar with an optional limit marker (e.g. the 85% yard threshold). */
export function Meter({ ratio, limit, critical = 0.95, label }: MeterProps) {
  const tone = ratio >= critical ? 'crit' : limit !== undefined && ratio > limit ? 'warn' : 'ok';
  return (
    <div
      className="meter"
      data-tone={tone}
      role="meter"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(ratio * 100)}
    >
      <span style={{ width: `${Math.min(100, ratio * 100)}%` }} />
      {limit !== undefined && <i className="limit" style={{ left: `${limit * 100}%` }} aria-hidden="true" />}
    </div>
  );
}
