import { useCallback, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { canManageUsers } from '../auth/permissions';
import { Forbidden } from '../components/Forbidden';
import { ErrorState, LoadingState, Panel, PageHeader } from '../components/Shared';
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
    <PageHeader title="Users and roles" description="Manage access to the operations console." onRefresh={reload} loading={loading} />
    <Panel title="Application users">
      {loading ? <LoadingState /> : error ? <ErrorState resource="users" detail={error} retry={reload} /> :
        <div className="admin-users"><div className="admin-user admin-list-header" aria-hidden="true"><span>User</span><span>Role</span><span>Created</span><span>Actions</span></div>{data?.map(user => <UserRow key={user.id} initialUser={user} />)}</div>}
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
      </div><div><span className="subtle">Current role: {user.role}</span></div><div className="date">{formatTime(user.createdAt)}</div>
    <form onSubmit={save} aria-busy={pending}>
      <label htmlFor={'role-' + user.id}>Role for {user.email}</label>
      <div className="detail-buttons">
        <select id={'role-' + user.id} value={role} disabled={pending} onChange={event => setRole(event.target.value as UserRole)}>
          <option value="NO_ACCESS">NO_ACCESS</option><option value="VIEWER">VIEWER</option><option value="OPERATOR">OPERATOR</option><option value="ADMIN">ADMIN</option>
        </select>
        <button className="button primary" type="submit" disabled={pending || role === user.role}>{pending ? 'Saving…' : 'Save role'}</button>
      </div>
      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
    </form>
  </section>;
}
