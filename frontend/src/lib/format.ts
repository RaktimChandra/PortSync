/** Formatting helpers. All business times are simulated time, shown in UTC like a port's ops clock. */

export function clock(iso: string | null | undefined): string {
  if (!iso) return '–';
  const d = new Date(iso);
  return d.toISOString().slice(11, 16);
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '–';
  const d = new Date(iso);
  return `${d.toISOString().slice(5, 10)} ${d.toISOString().slice(11, 16)}`;
}

export function minutesBetween(fromIso: string | null | undefined, toIso: string | null | undefined): number | null {
  if (!fromIso || !toIso) return null;
  return Math.max(0, Math.round((new Date(toIso).getTime() - new Date(fromIso).getTime()) / 60_000));
}

export function percent(ratio: number, digits = 0): string {
  return `${(ratio * 100).toFixed(digits)}%`;
}

export function ruleName(rule: string): string {
  switch (rule) {
    case 'CRANE_LOW_PRODUCTIVITY':
      return 'Crane productivity';
    case 'CRANE_FAULT_DURATION':
      return 'Crane down too long';
    case 'VESSEL_BERTH_WAIT':
      return 'Vessel waiting for berth';
    case 'TRUCK_TURN_TIME':
      return 'Truck turn time';
    case 'YARD_BLOCK_UTILISATION':
      return 'Yard block full';
    default:
      return rule;
  }
}

/** Readable form of SCREAMING_CASE enum values, e.g. CONTAINER_NOT_IN_YARD -> Container not in yard. */
export function humanise(value: string | null | undefined): string {
  if (!value) return '';
  const lower = value.toLowerCase().replace(/_/g, ' ');
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}
