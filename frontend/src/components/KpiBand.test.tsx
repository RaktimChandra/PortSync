import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Overview } from '../api/types';
import { KpiBand } from './KpiBand';

const overview: Overview = {
  simulatedTime: '2026-10-14T09:00:00Z', clockSynchronised: true, vesselsAtBerth: 2, vesselsWaiting: 1,
  craneMovesLastHour: 87, yardUtilisation: 0.6125, openAlerts: 3, acknowledgedAlerts: 1, avgTruckTurnMinutes: 31.4,
  trucksInTerminal: 9, cranesFaulted: 1, eventsProcessed: 1200, dlqEvents: 2,
  blocks: [
    { block: 'A', used: 120, capacity: 240, utilisation: 0.5 },
    { block: 'D', used: 210, capacity: 240, utilisation: 0.875 },
  ],
};

describe('KpiBand', () => {
  it('renders the five headline figures', () => {
    render(<KpiBand overview={overview} />);
    expect(screen.getByText('Vessels at berth')).toBeInTheDocument();
    expect(screen.getByText('87')).toBeInTheDocument();
    expect(screen.getByText('61%')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText('31')).toBeInTheDocument();
    expect(screen.getByText('1 crane down')).toBeInTheDocument();
    expect(screen.getByText('Fullest block D')).toBeInTheDocument();
  });

  it('shows a dash when no truck has left in the last hour', () => {
    render(<KpiBand overview={{ ...overview, avgTruckTurnMinutes: null }} />);
    expect(screen.getByText('–')).toBeInTheDocument();
  });
});
