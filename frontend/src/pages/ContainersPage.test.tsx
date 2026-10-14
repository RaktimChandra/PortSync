import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ContainerDetail } from '../api/types';
import { ContainersPage } from './ContainersPage';

const detail: ContainerDetail = {
  number: 'CSQU3054383',
  valid: true,
  status: 'IN_YARD',
  vesselId: null,
  location: { block: 'B', row: 2, bay: 7, tier: 1, label: 'B-02-07-1' },
  lastEventAt: '2026-10-14T09:10:00Z',
  history: [
    { eventId: 'e1', type: 'GATE_IN', occurredAt: '2026-10-14T09:00:00Z', description: 'Delivered by truck TV-000012 at lane G1' },
    { eventId: 'e2', type: 'YARD_MOVE', occurredAt: '2026-10-14T09:10:00Z', description: 'Stacked at B-02-07-1' },
  ],
  rejections: [],
};

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ContainersPage />
    </MemoryRouter>,
  );
}

function respond(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));
}

describe('ContainersPage', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('explains a wrong check digit while typing and never calls the API', async () => {
    renderAt('/containers');
    await userEvent.type(screen.getByLabelText('Container number'), 'CSQU3054384');

    expect(screen.getByText('Check digit should be 3, not 4')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Look up' })).toBeDisabled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('looks up a valid number from the URL and shows its location and history', async () => {
    fetchMock.mockImplementation(() => respond(200, detail));
    renderAt('/containers?number=CSQU3054383');

    expect(await screen.findByText('B-02-07-1')).toBeInTheDocument();
    expect(screen.getByText('In yard')).toBeInTheDocument();
    expect(screen.getByText('Delivered by truck TV-000012 at lane G1')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('/api/containers/CSQU3054383', expect.anything());
  });

  it('says so when a valid number has never been seen', async () => {
    fetchMock.mockImplementation(() => respond(404, { status: 404, code: 'CONTAINER_NOT_FOUND', message: 'Unknown' }));
    renderAt('/containers?number=MSKU9070323');

    expect(await screen.findByText(/MSKU9070323 is a valid number, but it has never been seen/)).toBeInTheDocument();
  });
});
