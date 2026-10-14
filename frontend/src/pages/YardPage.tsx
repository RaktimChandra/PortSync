import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Stack } from '../api/types';
import { Meter } from '../components/Meter';
import { ErrorNotice } from '../components/Notice';
import { YardGrid } from '../components/YardGrid';
import { useRefreshKey } from '../live/LiveContext';
import { useResource } from '../live/useResource';
import { percent } from '../lib/format';

const BLOCKS = ['A', 'B', 'C', 'D'];

export function YardPage() {
  const [params, setParams] = useSearchParams();
  const blockId = BLOCKS.includes(params.get('block') ?? '') ? (params.get('block') as string) : 'A';
  const block = useResource(() => api.block(blockId), `${blockId}:${useRefreshKey('YARD')}`);
  const [selected, setSelected] = useState<Stack | null>(null);

  // Keep the side panel in sync with fresh data for the same stack.
  const current = selected && block.data
    ? block.data.stacks.find((s) => s.row === selected.row && s.bay === selected.bay) ?? null
    : null;

  return (
    <>
      <header className="page-head">
        <h1>Yard</h1>
        <div className="block-tabs" role="tablist" aria-label="Yard block">
          {BLOCKS.map((b) => (
            <button key={b} type="button" role="tab" aria-selected={b === blockId}
                    onClick={() => { setParams({ block: b }); setSelected(null); }}>
              {b}
            </button>
          ))}
        </div>
      </header>
      <ErrorNotice error={block.error} />
      {block.data && (
        <>
          <div className="panel block-bar" style={{ marginBottom: '1.25rem' }}>
            <div className="block-bar-head">
              <strong>Block {block.data.block}</strong>
              <span>{percent(block.data.utilisation, 1)} full <span className="muted">({block.data.used} of {block.data.capacity} slots; alert above 85%)</span></span>
            </div>
            <Meter ratio={block.data.utilisation} limit={0.85} label={`Block ${block.data.block} utilisation`} />
          </div>
          <div className="yard-layout">
            <YardGrid block={block.data} selected={current} onSelect={setSelected} />
            <aside className="panel slot-panel" aria-live="polite">
              {current ? (
                <>
                  <h3>Row {current.row}, bay {current.bay}</h3>
                  <p className="muted">{current.height} of {block.data.tiers} tiers used. Tier 1 is the ground.</p>
                  <ul className="slot-list">
                    {current.slots.map((slot) => (
                      <li key={slot.tier}>
                        <span className="muted">Tier {slot.tier}</span>
                        {slot.containerNumber ? (
                          <Link className="code" to={`/containers?number=${slot.containerNumber}`}>{slot.containerNumber}</Link>
                        ) : (
                          <span className="muted">Empty</span>
                        )}
                      </li>
                    ))}
                  </ul>
                </>
              ) : (
                <p className="muted">Select a stack to see the containers in it.</p>
              )}
            </aside>
          </div>
        </>
      )}
    </>
  );
}
