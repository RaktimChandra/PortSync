import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Alert } from '../api/types';
import { AlertsTable } from './AlertsTable';

const base: Alert = {
  id: 1, rule: 'CRANE_FAULT_DURATION', entityId: 'QC03', severity: 'CRITICAL', state: 'OPEN',
  message: 'QC03 has been faulted (SPREADER_JAM) for 22 min (maximum 15 min)', observedValue: 22, threshold: 15,
  openedAt: '2026-10-14T08:30:00Z', lastSeenAt: '2026-10-14T08:35:00Z', acknowledgedAt: null, resolvedAt: null,
  resolution: null,
};

describe('AlertsTable', () => {
  it('shows a helpful empty state', () => {
    render(<AlertsTable alerts={[]} />);
    expect(screen.getByText(/No alerts/)).toBeInTheDocument();
  });

  it('shows the rule in plain words and the message', () => {
    render(<AlertsTable alerts={[base]} />);
    expect(screen.getByText('Crane down too long')).toBeInTheDocument();
    expect(screen.getByText(/SPREADER_JAM/)).toBeInTheDocument();
  });

  it('acknowledges and resolves through the callbacks', async () => {
    const ack = vi.fn();
    const resolve = vi.fn();
    render(<AlertsTable alerts={[base]} onAcknowledge={ack} onResolve={resolve} />);
    await userEvent.click(screen.getByRole('button', { name: 'Acknowledge alert 1' }));
    await userEvent.click(screen.getByRole('button', { name: 'Resolve alert 1' }));
    expect(ack).toHaveBeenCalledWith(base);
    expect(resolve).toHaveBeenCalledWith(base);
  });

  it('only allows the transitions the API allows', () => {
    const acked = { ...base, id: 2, state: 'ACKNOWLEDGED' as const };
    const resolved = { ...base, id: 3, state: 'RESOLVED' as const, resolution: 'AUTO' as const };
    render(<AlertsTable alerts={[acked, resolved]} onAcknowledge={() => {}} onResolve={() => {}} />);
    expect(screen.getByRole('button', { name: 'Acknowledge alert 2' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Resolve alert 2' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Resolve alert 3' })).toBeDisabled();
    expect(screen.getByText('Cleared by itself')).toBeInTheDocument();
  });
});
