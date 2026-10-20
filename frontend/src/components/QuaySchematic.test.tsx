import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Berth } from '../api/types';
import { QuaySchematic } from './QuaySchematic';

const worked: Berth = {
  id: 'B1',
  cranes: [],
  vessel: {
    id: 'V-0001', name: 'Eastern Star', voyage: '042E', status: 'AT_BERTH', berthId: 'B1',
    arrivedAt: '2026-10-20T08:00:00Z', berthedAt: '2026-10-20T09:00:00Z', departedAt: null,
    dischargePlanned: 30, discharged: 30, loadPlanned: 28, loaded: 28,
  },
};

describe('QuaySchematic', () => {
  it('fills the hull with the share of moves done', () => {
    const { container } = render(<QuaySchematic berths={[worked]} />);
    expect(screen.getByTitle('Eastern Star: 58 of 58 moves')).toBeInTheDocument();
    expect(container.querySelector('.hull-fill')).toHaveStyle({ width: '100%' });
  });

  it('does not draw the vessel code muted: it sits on the fill once the vessel is worked', () => {
    // --muted on the fill was 3.03:1 (light) / 2.97:1 (dark); the label takes --ink there,
    // see src/test/contrast.test.ts.
    render(<QuaySchematic berths={[worked]} />);
    const code = screen.getByText('V-0001');
    expect(code).toHaveClass('code');
    expect(code).not.toHaveClass('muted');
    expect(code.closest('.hull-label')?.previousElementSibling).toHaveClass('hull-fill');
  });
});
