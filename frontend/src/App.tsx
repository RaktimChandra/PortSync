import { Navigate, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { AlertsPage } from './pages/AlertsPage';
import { BerthsPage } from './pages/BerthsPage';
import { ContainersPage } from './pages/ContainersPage';
import { EventsPage } from './pages/EventsPage';
import { OverviewPage } from './pages/OverviewPage';
import { YardPage } from './pages/YardPage';

export function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<OverviewPage />} />
        <Route path="/berths" element={<BerthsPage />} />
        <Route path="/yard" element={<YardPage />} />
        <Route path="/containers" element={<ContainersPage />} />
        <Route path="/alerts" element={<AlertsPage />} />
        <Route path="/events" element={<EventsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Layout>
  );
}
