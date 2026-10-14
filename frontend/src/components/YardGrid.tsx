import type { Stack, YardBlock } from '../api/types';

interface YardGridProps {
  block: YardBlock;
  selected: Stack | null;
  onSelect: (stack: Stack) => void;
}

/**
 * Plan view of one block: rows down, bays across. Each cell is a stack drawn as its four tiers,
 * filled from the ground up and coloured by height, so a glance shows where the yard is tall.
 */
export function YardGrid({ block, selected, onSelect }: YardGridProps) {
  const bays = Array.from({ length: block.bays }, (_, i) => i + 1);
  const rows = Array.from({ length: block.rows }, (_, i) => i + 1);
  const byPosition = new Map(block.stacks.map((s) => [`${s.row}-${s.bay}`, s]));

  return (
    <div className="yard-grid panel">
      <table aria-label={`Block ${block.block} stacks`}>
        <thead>
          <tr>
            <th scope="col"><span className="sr-only">Row</span></th>
            {bays.map((bay) => (
              <th scope="col" key={bay}>Bay {bay}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row}>
              <th scope="row">Row {row}</th>
              {bays.map((bay) => {
                const stack = byPosition.get(`${row}-${bay}`);
                if (!stack) return <td key={bay} />;
                const isSelected = selected?.row === row && selected?.bay === bay;
                return (
                  <td key={bay}>
                    <button
                      type="button"
                      className="stack"
                      data-height={stack.height}
                      aria-pressed={isSelected}
                      aria-label={`Row ${row} bay ${bay}: ${stack.height} of ${block.tiers} tiers used`}
                      onClick={() => onSelect(stack)}
                    >
                      {Array.from({ length: block.tiers }, (_, i) => (
                        <span key={i} className={`tier${i < stack.height ? ' filled' : ''}`} />
                      ))}
                    </button>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <div className="legend" aria-hidden="true">
        <span><i style={{ background: 'var(--tier-1)' }} />1 high</span>
        <span><i style={{ background: 'var(--tier-2)' }} />2 high</span>
        <span><i style={{ background: 'var(--tier-3)' }} />3 high</span>
        <span><i style={{ background: 'var(--tier-4)' }} />4 high (full)</span>
      </div>
    </div>
  );
}
