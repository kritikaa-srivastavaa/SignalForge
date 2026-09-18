import { GroupAccessRequests } from './pages/GroupAccessRequests';
import { SignalMark } from './components/SignalMark';
import { Access } from './pages/Access';
import { AdminAccessRequests } from './pages/AdminAccessRequests';
import { AdminAudit } from './pages/AdminAudit';
import { canManageUsers, canReadOperations, canReviewGroup } from './auth/permissions';
import { AdminUsers } from './pages/AdminUsers';
import { AuthProvider, useAuth } from './auth/AuthProvider';
import { AuthRoutes } from './auth/AuthRoutes';
import { SessionIdentity } from './auth/SessionIdentity';
import { NavLink, Route, Routes, Link, useLocation } from 'react-router';
import { useEffect, useRef, useState } from 'react';
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
  return <AuthProvider><AuthRoutes><Console /></AuthRoutes></AuthProvider>;
}

function Console() {
  const { user } = useAuth();
  const location = useLocation();
  const main = useRef<HTMLElement>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const sidebar = useRef<HTMLElement>(null);
  function closeMenu() { setMenuOpen(false); menuButton.current?.focus(); }
  useEffect(() => {
    if (!menuOpen) return;
    sidebar.current?.querySelector<HTMLButtonElement>('button')?.focus();
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    function keydown(event: KeyboardEvent) {
      if (event.key === 'Escape') { event.preventDefault(); closeMenu(); }
      if (event.key === 'Tab') {
        const controls = sidebar.current?.querySelectorAll<HTMLElement>('a, button');
        if (!controls?.length) return;
        const first = controls[0], last = controls[controls.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    }
    function resize() { if (window.innerWidth > 760) setMenuOpen(false); }
    window.addEventListener('keydown', keydown); window.addEventListener('resize', resize);
    return () => { document.body.style.overflow = previous; window.removeEventListener('keydown', keydown); window.removeEventListener('resize', resize); };
  }, [menuOpen]);
  useEffect(() => {
    const title = location.pathname === '/access/review' ? 'Access requests' : location.pathname === '/access' ? 'My access' : location.pathname === '/admin/access-requests' ? 'Access requests' : location.pathname === '/admin/audit' ? 'Audit log' : location.pathname === '/admin/users' ? 'Users and roles' : location.pathname === '/' ? 'Overview' : location.pathname === '/events' ? 'Events' : location.pathname.startsWith('/incidents/') ? 'Incident details' : location.pathname === '/incidents' ? 'Incidents' : 'Not found';
    document.title = `${title} · SignalForge`;
    setMenuOpen(false);
    main.current?.focus();
  }, [location.pathname]);
  return <div className="app-shell">
    <a className="skip-link" href="#main">Skip to content</a>
    {menuOpen && <button className="nav-backdrop" aria-label="Dismiss navigation" onClick={closeMenu} tabIndex={-1} />}
    <aside ref={sidebar} id="console-navigation" className={'sidebar' + (menuOpen ? ' is-open' : '')}>
      <button className="button menu-close" onClick={closeMenu}>Close navigation</button>
      <Link className="brand" to="/"><SignalMark /><span>SignalForge<small>EVENT OPERATIONS</small></span></Link>
      <p className="nav-label">WORKSPACE</p>
      <nav aria-label="Main navigation">
        {canReadOperations(user) && <><NavLink to="/" end><NavIcon kind="overview" />Overview</NavLink>
        <NavLink to="/events"><NavIcon kind="events" />Events</NavLink>
        <NavLink to="/incidents"><NavIcon kind="incidents" />Incidents</NavLink></>}
        <NavLink to="/access" end>My access</NavLink>
        {canReviewGroup(user) && <NavLink to="/access/review">Access Requests</NavLink>}
        {canManageUsers(user) && <><p className="nav-label">ADMINISTRATION</p><NavLink to="/admin/users" aria-label="Users">Users</NavLink><NavLink to="/admin/access-requests">Access Requests</NavLink><NavLink to="/admin/audit">Audit Log</NavLink></>}

      </nav>
      <div className="sidebar-footer"><div>Local workspace<small>Incident operations</small></div></div>
    </aside>
    <div className="workspace"><div className="topbar"><button ref={menuButton} className="button secondary menu-toggle" aria-expanded={menuOpen} aria-controls="console-navigation" onClick={() => setMenuOpen(true)}>Menu</button><span className="topbar-label">Operations console</span><SessionIdentity /></div>
      <main id="main" ref={main} tabIndex={-1}>
        <Routes>
          <Route path="/" element={<Overview />} />
          <Route path="/events" element={<CollectionPage key="events" title="Events" description="Explore incoming telemetry, one event at a time." load={getEvents} table={rows => <EventsTable events={rows} />} />} />
          <Route path="/incidents" element={<CollectionPage key="incidents" title="Incidents" description="Track detected incidents and their current status." load={getIncidents} table={rows => <IncidentsTable incidents={rows} />} />} />
          <Route path="/incidents/:id" element={<IncidentDetail />} />
          <Route path="/access/review" element={<GroupAccessRequests />} />
          <Route path="/access" element={<Access />} />
          <Route path="/admin/access-requests" element={<AdminAccessRequests />} />
          <Route path="/admin/audit" element={<AdminAudit />} />
          <Route path="/admin/users" element={<AdminUsers />} />
          <Route path="*" element={<div className="state route-state"><p className="eyebrow">404</p><h1>Page not found</h1><p>This page isn’t part of the workspace.</p><Link className="text-link" to="/">Back to Overview</Link></div>} />
        </Routes>
      </main><footer className="workspace-footer">SignalForge<span>Event intelligence. Operational clarity.</span></footer>
    </div>
  </div>;
}
