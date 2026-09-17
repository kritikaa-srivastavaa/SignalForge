import { useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { ApiError } from '../api/client';

export function AuthPage({ register = false }: { register?: boolean }) {
  const auth = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const submitting = useRef(false);

  async function submit(event: React.SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) return;
    submitting.current = true;
    setPending(true);
    setError('');
    try {
      if (register) {
        await auth.register({ email, password, displayName });
        setPassword('');
        navigate('/login', { replace: true, state: { from: location.state?.from, registered: true } });
      } else {
        await auth.login({ email, password });
        setPassword('');
      }
    } catch (failure: unknown) {
      if (failure instanceof ApiError && failure.status === 401) setError('Invalid email or password');
      else if (register && failure instanceof ApiError && failure.status === 409) setError('An account with this email already exists. Log in instead.');
      else if (failure instanceof ApiError && failure.status === 400) setError('Check your email and password requirements' + (register ? ', and enter a display name.' : '.'));
      else setError(failure instanceof ApiError ? failure.message : 'Unable to connect. Please try again.');
    } finally {
      submitting.current = false;
      setPending(false);
    }
  }

  return <main className="auth-page"><section className="auth-card">
    <p className="eyebrow">SIGNALFORGE</p>
    <h1>{register ? 'Create account' : 'Log in'}</h1>
    <p className="subtle">{register ? 'Create your local SignalForge account, then log in.' : 'Log in to open your operations console.'}</p>
    {location.state?.registered && !register && <p role="status">Account created. Log in to continue.</p>}
    <form onSubmit={submit} aria-busy={pending}>
      {register && <label>Display name<input required maxLength={100} autoComplete="name" value={displayName} onChange={event => setDisplayName(event.target.value)} /></label>}
      <label>Email<input type="email" required maxLength={254} autoComplete="username" value={email} onChange={event => setEmail(event.target.value)} /></label>
      <label>Password<input type="password" required minLength={register ? 8 : undefined} maxLength={72} autoComplete={register ? 'new-password' : 'current-password'} value={password} onChange={event => setPassword(event.target.value)} aria-describedby={register ? 'password-hint' : undefined} /></label>
      {register && <p id="password-hint" className="subtle">Use at least 8 characters and at most 72 UTF-8 bytes. Unicode characters may use multiple bytes.</p>}
      {error && <p role="alert">{error}</p>}
      <button className="button primary" disabled={pending} type="submit">{pending ? 'Please wait…' : register ? 'Create account' : 'Log in'}</button>
    </form>
    <p><Link className="text-link" to={register ? '/login' : '/register'} state={{ from: location.state?.from }}>{register ? 'Already have an account? Log in' : 'Create an account'}</Link></p>
  </section></main>;
}
