import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { App } from '../App';
import { safeDestination } from '../auth/AuthRoutes';
import { get, patch, post } from '../api/client';

const identity = { id: 'user-1', email: 'test@example.com', displayName: 'Test User', createdAt: '2026-09-17T10:00:00Z' };
const incident = { id: 'ca50dfc3-f812-4663-bad0-a49a88d38978', sourceEventId: 'event-1', service: 'payment',
  type: 'ERROR', severity: 'HIGH', title: 'Auth incident', status: 'OPEN', createdAt: identity.createdAt };
const page = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true };
const response = (value: unknown, status = 200) => status === 204 ? new Response(null, { status }) : new Response(JSON.stringify(value), { status });
const fetchMock = vi.fn<typeof fetch>();
let authenticated: boolean;
let loginStatus: number;
let registrationStatus: number;
let meStatus: number | undefined;
let csrfNumber: number;

beforeEach(() => {
  authenticated = false; loginStatus = 200; registrationStatus = 201; meStatus = undefined; csrfNumber = 0;
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (input, options) => {
    const path = new URL(String(input)).pathname;
    if (path === '/auth/me') return response(identity, meStatus ?? (authenticated ? 200 : 401));
    if (path === '/auth/csrf') return response({ headerName: 'X-CSRF-TOKEN', token: 'csrf-' + ++csrfNumber });
    if (path === '/auth/login') { authenticated = loginStatus === 200; return response(identity, loginStatus); }
    if (path === '/auth/register') return response(identity, registrationStatus);
    if (path === '/auth/logout') { authenticated = false; return response(null, 204); }
    if (!authenticated) return response({}, 401);
    if (path.endsWith('/acknowledge')) return response({ ...incident, status: 'ACKNOWLEDGED' });
    if (path === '/incidents/' + incident.id) return response(incident);
    if (options?.method === 'POST') return response({ id: 'event-1' }, 201);
    return response(page);
  });
  vi.stubGlobal('fetch', fetchMock);
});
const show = (path = '/') => render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
const calls = (path: string) => fetchMock.mock.calls.filter(([input]) => new URL(String(input)).pathname === path);
async function fillLogin() {
  await screen.findByRole('heading', { name: 'Log in' });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: identity.email } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'test-only-password' } });
}
async function submitLogin() {
  await fillLogin();
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }));
}
async function fillRegister() {
  await screen.findByRole('heading', { name: 'Create account' });
  fireEvent.change(screen.getByLabelText('Display name'), { target: { value: identity.displayName } });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: identity.email } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'test-only-password' } });
  await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
}

it('waits for session restoration without flashing protected content', async () => {
  let finish!: (value: Response) => void;
  fetchMock.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  show();
  expect(screen.getByRole('status')).toHaveTextContent('Checking your session');
  expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  await act(async () => finish(response({}, 401)));
  expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
});

it('shows login after an initial anonymous me response', async () => {
  show();
  expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
  expect(calls('/auth/me')).toHaveLength(1);
  expect(calls('/events')).toHaveLength(0);
  expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password');
  expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password');
});

it('logs in and displays the authenticated identity and console', async () => {
  show(); await submitLogin();
  expect(await screen.findByRole('heading', { name: 'Overview' })).toBeInTheDocument();
  expect(screen.getByText(identity.displayName)).toBeInTheDocument();
  expect(screen.getByText(identity.email)).toBeInTheDocument();
  expect(calls('/auth/login')).toHaveLength(1);
});

it('shows a generic invalid-credentials error', async () => {
  loginStatus = 401;
  show('/login'); await submitLogin();
  expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password');
  expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
});

it('prevents duplicate login submission while waiting', async () => {
  show('/login'); await fillLogin();
  const original = fetchMock.getMockImplementation()!;
  let finish!: (value: Response) => void;
  fetchMock.mockImplementation((input, options) => String(input).endsWith('/auth/login')
    ? new Promise(resolve => { finish = resolve; }) : original(input, options));
  const button = screen.getByRole('button', { name: 'Log in' });
  fireEvent.submit(button.closest('form')!);
  fireEvent.submit(button.closest('form')!);
  await waitFor(() => expect(calls('/auth/login')).toHaveLength(1));
  expect(screen.getByRole('button', { name: 'Please wait…' })).toBeDisabled();
  authenticated = true;
  await act(async () => finish(response(identity)));
  await screen.findByRole('heading', { name: 'Overview' });
});

it('registers and explicitly asks the user to log in afterward', async () => {
  show('/register'); await fillRegister();
  expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('Account created');
  expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  expect(calls('/auth/register')).toHaveLength(1);
  expect(calls('/auth/login')).toHaveLength(0);
  expect(screen.getByLabelText('Password')).toHaveValue('');
});

it('explains duplicate registration without exposing server internals', async () => {
  registrationStatus = 409;
  show('/register'); await fillRegister();
  expect(await screen.findByRole('alert')).toHaveTextContent('An account with this email already exists');
});

it('displays registration requirements and validation errors', async () => {
  registrationStatus = 400;
  show('/register');
  await screen.findByRole('heading', { name: 'Create account' });
  expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'new-password');
  expect(screen.getByText(/at least 8 characters/)).toBeInTheDocument();
  await fillRegister();
  expect(await screen.findByRole('alert')).toHaveTextContent('Check your email and password requirements');
});

it('restores an existing session on startup', async () => {
  authenticated = true;
  show('/events');
  await screen.findByRole('heading', { name: 'Events' });
  expect(calls('/auth/login')).toHaveLength(0);
  expect(screen.getByText(identity.displayName)).toBeInTheDocument();
});

it('protects a direct incident route and returns there after login', async () => {
  show('/incidents/' + incident.id);
  await fillLogin();
  expect(calls('/incidents/' + incident.id)).toHaveLength(0);
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }));
  expect(await screen.findByRole('heading', { name: incident.title })).toBeInTheDocument();
});

it('only allows known internal login return destinations', () => {
  for (const path of ['https://evil.example', '//evil.example', '/\\evil.example', '/login', '/events?next=https://evil.example']) {
    expect(safeDestination(path)).toBe('/');
  }
  expect(safeDestination('/incidents/' + incident.id)).toBe('/incidents/' + incident.id);
});

it('logs out on the server and removes protected content', async () => {
  authenticated = true;
  show('/events'); await screen.findByRole('heading', { name: 'Events' });
  await userEvent.click(screen.getByRole('button', { name: 'Logout' }));
  expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
  expect(calls('/auth/logout')).toHaveLength(1);
  expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  expect(screen.queryByText(identity.email)).not.toBeInTheDocument();
});

it('removes the console when a protected API reports session expiry', async () => {
  authenticated = true;
  show('/events'); await screen.findByRole('button', { name: 'Refresh' });
  await screen.findByText('No events found.');
  authenticated = false;
  await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
  expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
  expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
});

it('keeps startup failures distinct from an anonymous session and allows retry', async () => {
  meStatus = 500;
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('Unable to check your session');
  meStatus = 401;
  await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
  await screen.findByRole('heading', { name: 'Log in' });
});

it('uses cookies and fresh CSRF headers centrally for all mutations', async () => {
  authenticated = true;
  await get('/events');
  await post('/auth/login', { email: identity.email, password: 'test-only-password' });
  await patch('/incidents/' + incident.id + '/acknowledge');
  await post('/auth/logout');
  expect(fetchMock.mock.calls.every(([, options]) => options?.credentials === 'include')).toBe(true);
  const mutations = fetchMock.mock.calls.filter(([, options]) => ['POST', 'PATCH'].includes(options?.method || ''));
  expect(mutations.map(([, options]) => new Headers(options?.headers).get('X-CSRF-TOKEN')))
    .toEqual(['csrf-1', 'csrf-2', 'csrf-3']);
  for (const [, options] of fetchMock.mock.calls.filter(([, options]) => !options?.method)) {
    expect(options?.headers).toBeUndefined();
  }
});

it('preserves incident lifecycle behavior with an authenticated session', async () => {
  authenticated = true;
  show('/incidents/' + incident.id);
  await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }));
  expect(await screen.findByText('ACKNOWLEDGED')).toBeInTheDocument();
  const mutation = calls('/incidents/' + incident.id + '/acknowledge')[0][1];
  expect(mutation?.method).toBe('PATCH');
  expect(new Headers(mutation?.headers).get('X-CSRF-TOKEN')).toBe('csrf-1');
});

it('shows safe unexpected login failure feedback', async () => {
  loginStatus = 500;
  show('/login'); await submitLogin();
  expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 500');
  expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled();
});
