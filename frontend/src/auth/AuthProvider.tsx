import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import * as api from '../api/auth';
import { ApiError } from '../api/client';
import type { User } from '../types';

interface AuthState {
  user: User | null;
  loading: boolean;
  error: string | null;
  login: (input: api.LoginInput) => Promise<void>;
  register: (input: api.RegisterInput) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => void;
  updateCurrentUser: (user: User) => void;
}
const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    api.getCurrentUser(controller.signal).then(identity => {
      if (!controller.signal.aborted) setUser(identity);
    }).catch((failure: unknown) => {
      if (controller.signal.aborted) return;
      setUser(null);
      if (!(failure instanceof ApiError && failure.status === 401)) {
        setError('Unable to check your session. Check the connection and retry.');
      }
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [revision]);

  useEffect(() => {
    const expired = () => setUser(null);
    const permissionsChanged = () => setRevision(value => value + 1);
    window.addEventListener('signalforge:permissions-changed', permissionsChanged);
    window.addEventListener('signalforge:unauthenticated', expired);
    return () => {
      window.removeEventListener('signalforge:unauthenticated', expired);
      window.removeEventListener('signalforge:permissions-changed', permissionsChanged);
    };
  }, []);

  const value: AuthState = {
    user, loading, error,
    updateCurrentUser(updated) { if (updated.id === user?.id) setUser(updated); },
    async login(input) { setUser(await api.login(input)); },
    async register(input) { await api.register(input); },
    async logout() { await api.logout(); setUser(null); },
    refreshUser() { setRevision(value => value + 1); },
  };
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const auth = useContext(AuthContext);
  if (!auth) throw new Error('AuthProvider is required');
  return auth;
}
