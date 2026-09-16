import { NavLink, Route, Routes, Link, useLocation } from 'react-router';
import { useEffect, useRef } from 'react';
import { getEvents } from './api/events';
import { getIncidents } from './api/incidents';
import { EventsTable, IncidentsTable } from './components/Tables';
import { CollectionPage } from './pages/CollectionPage';
import { Overview } from './pages/Overview';
import { IncidentDetail } from './pages/IncidentDetail';

function NavIcon({ kind }: { kind: 'overview' | 'events' | 'incidents' }) {
  return <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
    {kind === 'overview' ? <path d="M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z" /> : kind === 'events' ? <path d="M3 12h4l3-7 4 14 3-7h4" /> : <><path d="m12 3 10 18H2L12 3Z" /><path d="M12 9v5m0 3v1" /></>}
  </svg>;
}
export function App() {
  const location = useLocation();
  const main = useRef<HTMLElement>(null);
  useEffect(() => {
    const title = location.pathname === '/' ? 'Overview' : location.pathname === '/events' ? 'Events' : location.pathname.startsWith('/incidents/') ? 'Incident details' : location.pathname === '/incidents' ? 'Incidents' : 'Not found';
    document.title = `${title} · SignalForge`;
    main.current?.focus();
  }, [location.pathname]);
  return <div className="app-shell">
    <a className="skip-link" href="#main">Skip to content</a>
    <aside className="sidebar">
      <Link className="brand" to="/"><span className="brand-mark" aria-hidden="true">S<span>F</span></span><span>SignalForge<small>EVENT OPERATIONS</small></span></Link>
      <p className="nav-label">WORKSPACE</p>
      <nav aria-label="Main navigation">
        <NavLink to="/" end><NavIcon kind="overview" />Overview</NavLink>
        <NavLink to="/events"><NavIcon kind="events" />Events</NavLink>
        <NavLink to="/incidents"><NavIcon kind="incidents" />Incidents</NavLink>
      </nav>
      <div className="sidebar-footer"><span className="workspace-avatar" aria-hidden="true">L</span><div>Local workspace<small>Incident operations</small></div></div>
    </aside>
    <div className="workspace"><div className="topbar"><span>Operations console</span><span className="environment">LOCAL DEVELOPMENT</span></div>
      <main id="main" ref={main} tabIndex={-1}>
        <Routes>
          <Route path="/" element={<Overview />} />
          <Route path="/events" element={<CollectionPage key="events" title="Events" description="Explore incoming telemetry, one event at a time." load={getEvents} table={rows => <EventsTable events={rows} />} />} />
          <Route path="/incidents" element={<CollectionPage key="incidents" title="Incidents" description="Track detected incidents and their current status." load={getIncidents} table={rows => <IncidentsTable incidents={rows} />} />} />
          <Route path="/incidents/:id" element={<IncidentDetail />} />
          <Route path="*" element={<div className="state"><p className="eyebrow">404</p><h1>Page not found</h1><p>This page isn’t part of the workspace.</p><Link className="text-link" to="/">Back to Overview</Link></div>} />
        </Routes>
      </main><footer className="workspace-footer">SignalForge<span>Event intelligence. Operational clarity.</span></footer>
    </div>
  </div>;
}
