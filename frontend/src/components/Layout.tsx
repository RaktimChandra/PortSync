import { useEffect, useState, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { api } from '../api/client';
import { useLive, useRefreshKey } from '../live/LiveContext';
import { useResource } from '../live/useResource';
import { clock } from '../lib/format';

type Theme = 'system' | 'light' | 'dark';

function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(() => {
    try {
      return (localStorage.getItem('portsync-theme') as Theme) ?? 'system';
    } catch {
      return 'system';
    }
  });
  useEffect(() => {
    if (theme === 'system') document.documentElement.removeAttribute('data-theme');
    else document.documentElement.setAttribute('data-theme', theme);
    try {
      localStorage.setItem('portsync-theme', theme);
    } catch {
      // storage unavailable: theme still applies for this visit
    }
  }, [theme]);
  const next = () => setTheme((t) => (t === 'system' ? 'light' : t === 'light' ? 'dark' : 'system'));
  return [theme, next];
}

const statusText = {
  connecting: 'Connecting',
  open: 'Live',
  reconnecting: 'Reconnecting',
  closed: 'Offline',
} as const;

export function Layout({ children }: { children: ReactNode }) {
  const { status } = useLive();
  const overview = useResource(api.overview, useRefreshKey('ALERTS', 'CRANES', 'YARD'), 5_000);
  const [theme, toggleTheme] = useTheme();
  const open = overview.data?.openAlerts ?? 0;

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <svg width="30" height="30" viewBox="0 0 32 32" aria-hidden="true">
            <rect x="4" y="18" width="24" height="7" fill="var(--ok)" />
            <rect x="4" y="10" width="15" height="7" fill="var(--signal)" />
            <rect x="20" y="10" width="8" height="7" fill="var(--info)" />
          </svg>
          <div>
            PortSync
            <small>Terminal operations (simulated)</small>
          </div>
        </div>
        <nav className="nav" aria-label="Main">
          <NavLink to="/" end>Overview</NavLink>
          <NavLink to="/berths">Berths and cranes</NavLink>
          <NavLink to="/yard">Yard</NavLink>
          <NavLink to="/containers">Container lookup</NavLink>
          <NavLink to="/alerts">
            Alerts
            {open > 0 && <span className="count" aria-label={`${open} open`}>{open}</span>}
          </NavLink>
          <NavLink to="/events">Events and DLQ</NavLink>
        </nav>
        <div className="sidebar-foot">
          <div>
            <div className="sim-clock" aria-label="Simulated time">{clock(overview.data?.simulatedTime)}</div>
            <div className="muted">Simulated time, UTC</div>
          </div>
          <span className="connection" data-status={status} role="status">{statusText[status]}</span>
          <button className="theme-toggle" type="button" onClick={toggleTheme}>
            Theme: {theme === 'system' ? 'system' : theme}
          </button>
        </div>
      </aside>
      <main className="main">{children}</main>
    </div>
  );
}
