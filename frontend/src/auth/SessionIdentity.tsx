import { useRef, useState } from 'react';
import { useAuth } from './AuthProvider';

export function SessionIdentity() {
  const { user, logout } = useAuth();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const submitting = useRef(false);
  async function signOut() {
    if (submitting.current) return;
    submitting.current = true;
    setPending(true);
    setError('');
    try { await logout(); }
    catch { setError('Unable to log out. Please retry.'); }
    finally { submitting.current = false; setPending(false); }
  }
  return <div className="session-identity">
    <span>{user?.displayName}<small><span>{user?.email}</span> · {user?.role}</small></span>
    <button className="button secondary" disabled={pending} onClick={() => void signOut()}>{pending ? 'Logging out…' : 'Logout'}</button>
    {error && <span role="alert">{error}</span>}
  </div>;
}
