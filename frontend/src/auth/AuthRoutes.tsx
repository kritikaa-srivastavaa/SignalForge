import { Navigate, Route, Routes, useLocation } from 'react-router';
import type { ReactNode } from 'react';
import { useAuth } from './AuthProvider';
import { AuthPage } from '../pages/AuthPage';

// Only known application routes may be restored; never redirect to supplied external URLs.
export function safeDestination(value: unknown): string {
  return typeof value === 'string' && /^(\/$|\/access(?:\/review)?$|\/admin\/(users|access-requests|audit)$|\/events$|\/incidents$|\/incidents\/[0-9a-f-]+$)/i.test(value) ? value : '/';
}

export function AuthRoutes({ children }: { children: ReactNode }) {
  const { user, loading, error, refreshUser } = useAuth();
  const location = useLocation();
  if (loading) return <div className="state" role="status">Checking your session…</div>;
  if (error) return <div className="state" role="alert"><p>{error}</p><button className="button primary" onClick={refreshUser}>Retry</button></div>;
  if (user) {
    if (user.role === 'NO_ACCESS' && location.pathname !== '/access') return <Navigate to="/access" replace />;
    if (location.pathname === '/login' || location.pathname === '/register') {
      return <Navigate to={safeDestination(location.state?.from)} replace />;
    }
    return children;
  }
  return <Routes>
    <Route path="/login" element={<AuthPage key="login" />} />
    <Route path="/register" element={<AuthPage key="register" register />} />
    <Route path="*" element={<Navigate to="/login" replace state={{ from: safeDestination(location.pathname) }} />} />
  </Routes>;
}
