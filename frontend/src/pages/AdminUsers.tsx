import { useCallback, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { canManageUsers } from '../auth/permissions';
import { Forbidden } from '../components/Forbidden';
import { ErrorState, LoadingState, Panel } from '../components/Shared';
import { changeUserRole, getUsers } from '../api/users';
import { ApiError } from '../api/client';
import { useRemote } from '../hooks/useRemote';
import type { User, UserRole } from '../types';
import { formatTime } from '../utils/format';

export function AdminUsers() {
  const { user } = useAuth();
  return canManageUsers(user) ? <UserList /> : <Forbidden />;
}
function UserList() {
  const load = useCallback((signal: AbortSignal) => getUsers(signal), []);
  const { data, loading, error, reload } = useRemote(load);
  return <>
    <header className="page-header"><div><p className="eyebrow">ADMINISTRATION</p><h1>Users and roles</h1>
      <p className="subtle">Manage access to the operations console.</p></div></header>
    <Panel title="Application users">
      {loading ? <LoadingState /> : error ? <ErrorState resource="users" detail={error} retry={reload} /> :
        <div className="admin-users">{data?.map(user => <UserRow key={user.id} initialUser={user} />)}</div>}
    </Panel>
  </>;
}
function UserRow({ initialUser }: { initialUser: User }) {
  const { user: currentUser, updateCurrentUser } = useAuth();
  const [user, setUser] = useState(initialUser);
  const [role, setRole] = useState<UserRole>(initialUser.role);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const submitting = useRef(false);
  async function save(event: React.SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current || role === user.role) return;
    submitting.current = true;
    setPending(true); setError(''); setMessage('');
    try {
      const saved = await changeUserRole(user.id, role);
      setUser(saved); setRole(saved.role);
      setMessage('Role updated to ' + saved.role + '.');
      if (saved.id === currentUser?.id) updateCurrentUser(saved);
    } catch (failure: unknown) {
      setError(failure instanceof ApiError && failure.status === 409
        ? 'The last administrator cannot be demoted. Assign another administrator first.'
        : failure instanceof ApiError ? failure.message : 'Unable to update role. Please retry.');
    } finally { submitting.current = false; setPending(false); }
  }
  return <section className="admin-user" aria-label={user.email}>
    <div><strong>{user.displayName}</strong><p>{user.email}</p>
      <p className="subtle">Current role: {user.role} · Created {formatTime(user.createdAt)}</p></div>
    <form onSubmit={save} aria-busy={pending}>
      <label htmlFor={'role-' + user.id}>Role for {user.email}</label>
      <div className="detail-buttons">
        <select id={'role-' + user.id} value={role} disabled={pending} onChange={event => setRole(event.target.value as UserRole)}>
          <option value="VIEWER">VIEWER</option><option value="OPERATOR">OPERATOR</option><option value="ADMIN">ADMIN</option>
        </select>
        <button className="button primary" type="submit" disabled={pending || role === user.role}>{pending ? 'Saving…' : 'Save role'}</button>
      </div>
      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
    </form>
  </section>;
}
