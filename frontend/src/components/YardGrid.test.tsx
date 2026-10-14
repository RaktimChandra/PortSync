import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { YardBlock } from '../api/types';
import { YardGrid } from './YardGrid';

function block(): YardBlock {
  const stacks = [];
  for (let row = 1; row <= 2; row++) {
    for (let bay = 1; bay <= 3; bay++) {
      const height = row === 1 && bay === 2 ? 3 : 0;
      stacks.push({
        row, bay, height,
        slots: [1, 2, 3, 4].map((tier) => ({ tier, containerNumber: tier <= height ? `TSTU00000${tier}0` : null })),
      });
    }
  }
  return { block: 'C', rows: 2, bays: 3, tiers: 4, capacity: 24, used: 3, utilisation: 0.125, stacks };
}

describe('YardGrid', () => {
  it('draws one button per stack with its height', () => {
    render(<YardGrid block={block()} selected={null} onSelect={() => {}} />);
    expect(screen.getAllByRole('button')).toHaveLength(6);
    expect(screen.getByRole('button', { name: 'Row 1 bay 2: 3 of 4 tiers used' })).toHaveAttribute('data-height', '3');
  });

  it('reports the clicked stack', async () => {
    const onSelect = vi.fn();
    render(<YardGrid block={block()} selected={null} onSelect={onSelect} />);
    await userEvent.click(screen.getByRole('button', { name: /Row 1 bay 2/ }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ row: 1, bay: 2, height: 3 }));
  });

  it('marks the selected stack as pressed', () => {
    const b = block();
    render(<YardGrid block={b} selected={b.stacks[1]} onSelect={() => {}} />);
    expect(screen.getByRole('button', { name: /Row 1 bay 2/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /Row 1 bay 1/ })).toHaveAttribute('aria-pressed', 'false');
  });
});
