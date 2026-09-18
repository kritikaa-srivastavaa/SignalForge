import type { ReactNode } from 'react';
// These business tests run inside an authenticated boundary; Auth.test covers real state transitions.
vi.mock('../auth/AuthProvider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: () => ({
    user: { id: 'test-user', email: 'test@example.com', displayName: 'Test User', role: 'OPERATOR' },
    loading: false, error: null, logout: vi.fn(), refreshUser: vi.fn(),
  }),
}));
import { act, fireEvent, render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { Incident } from '../types';
import { formatTime } from '../utils/format';

const incident: Incident = {
  id: 'ca50dfc3-f812-4663-bad0-a49a88d38978',
  sourceEventId: 'aa50dfc3-f812-4663-bad0-a49a88d38978',
  service: 'payment-service', type: 'API_ERROR', severity: 'HIGH',
  title: 'Payment failure spike', status: 'OPEN', createdAt: '2026-09-16T10:30:01Z',
};
const fetchMock = vi.fn<typeof fetch>();
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
const updated = (status: Incident['status']) => ({ ...incident, status });
const page = (content: Incident[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1, first: true, last: true });
const show = (path = '/incidents/' + incident.id) => render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
const patches = () => fetchMock.mock.calls.filter(([, options]) => options?.method === 'PATCH');
beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', (input: RequestInfo | URL, options?: RequestInit) =>
  String(input).endsWith('/auth/csrf')
    ? Promise.resolve(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' })))
    : fetchMock(input, options)); });

it('loads real detail fields and offers both OPEN actions', async () => {
  fetchMock.mockResolvedValue(response(incident));
  show();
  expect(await screen.findByRole('heading', { name: incident.title })).toBeInTheDocument();
  for (const value of [incident.id, incident.sourceEventId, incident.service, incident.type, incident.severity, 'OPEN', formatTime(incident.createdAt)]) {
    expect(screen.getByText(value)).toBeInTheDocument();
  }
  expect(screen.getByRole('button', { name: 'Acknowledge' })).toBeEnabled();
  expect(screen.getByRole('button', { name: 'Resolve' })).toBeEnabled();
  expect(String(fetchMock.mock.calls[0][0])).toBe('http://localhost:8080/incidents/' + incident.id);
  expect(screen.getByRole('link', { name: /Back to incidents/ })).toHaveAttribute('href', '/incidents');
});

it('navigates from a semantic incident list link to its detail route', async () => {
  fetchMock.mockResolvedValueOnce(response(page([incident]))).mockResolvedValueOnce(response(incident));
  show('/incidents');
  const link = await screen.findByRole('link', { name: 'View incident ' + incident.id });
  expect(link).toHaveAttribute('href', '/incidents/' + incident.id);
  await userEvent.click(link);
  expect(await screen.findByRole('heading', { name: incident.title })).toBeInTheDocument();
  expect(String(fetchMock.mock.calls[1][0])).toContain('/incidents/' + incident.id);
});

it('acknowledges with a bodyless PATCH and uses the server response', async () => {
  fetchMock.mockResolvedValueOnce(response(incident)).mockResolvedValueOnce(response(updated('ACKNOWLEDGED')));
  show();
  await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }));
  expect(await screen.findByText('ACKNOWLEDGED')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Resolve' })).toBeEnabled();
  expect(patches()).toHaveLength(1);
  expect(String(patches()[0][0])).toBe('http://localhost:8080/incidents/' + incident.id + '/acknowledge');
  expect(patches()[0][1]?.body).toBeUndefined();
  expect(screen.getByRole('status')).toHaveTextContent('Incident acknowledged.');
});

it.each(['OPEN', 'ACKNOWLEDGED'] as const)('confirms and resolves an %s incident using a bodyless PATCH', async status => {
  fetchMock.mockResolvedValueOnce(response(updated(status))).mockResolvedValueOnce(response(updated('RESOLVED')));
  show();
  await userEvent.click(await screen.findByRole('button', { name: 'Resolve' }));
  expect(patches()).toHaveLength(0);
  expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
  await userEvent.click(screen.getByRole('button', { name: 'Confirm resolve' }));
  expect(await screen.findByText('RESOLVED')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /acknowledge|resolve/i })).not.toBeInTheDocument();
  expect(patches()).toHaveLength(1);
  expect(String(patches()[0][0])).toContain('/' + incident.id + '/resolve');
  expect(patches()[0][1]?.body).toBeUndefined();
  expect(screen.getByRole('status')).toHaveTextContent('Incident resolved.');
});

it('can cancel confirmation by keyboard without mutating', async () => {
  fetchMock.mockResolvedValue(response(incident));
  show();
  await userEvent.click(await screen.findByRole('button', { name: 'Resolve' }));
  await userEvent.keyboard('{Escape}');
  expect(screen.queryByRole('button', { name: 'Confirm resolve' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Resolve' })).toHaveFocus();
  expect(patches()).toHaveLength(0);
});

it('offers only resolve for ACKNOWLEDGED incidents', async () => {
  fetchMock.mockResolvedValue(response(updated('ACKNOWLEDGED')));
  show();
  expect(await screen.findByRole('button', { name: 'Resolve' })).toBeEnabled();
  expect(screen.queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument();
});

it('shows a terminal state without mutation actions for RESOLVED incidents', async () => {
  fetchMock.mockResolvedValue(response(updated('RESOLVED')));
  show();
  expect(await screen.findByText(/No further lifecycle actions/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /acknowledge|resolve/i })).not.toBeInTheDocument();
});

it.each(['acknowledge', 'resolve'] as const)('blocks duplicate %s requests and waits for server success', async action => {
  let finish!: (response: Response) => void;
  fetchMock.mockResolvedValueOnce(response(incident)).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  show();
  let button = await screen.findByRole('button', { name: action === 'acknowledge' ? 'Acknowledge' : 'Resolve' });
  if (action === 'resolve') {
    await userEvent.click(button);
    button = screen.getByRole('button', { name: 'Confirm resolve' });
  }
  fireEvent.click(button);
  fireEvent.click(button);
  await waitFor(() => expect(patches()).toHaveLength(1));
  expect(button).toBeDisabled();
  expect(screen.getByText('OPEN')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Refresh' })).toBeDisabled();
  expect(screen.getAllByRole('button', { name: action === 'resolve' ? 'Resolving...' : 'Acknowledging...' }).every(b => b.hasAttribute('disabled'))).toBe(true);
  await act(async () => finish(response(updated(action === 'resolve' ? 'RESOLVED' : 'ACKNOWLEDGED'))));
  expect(await screen.findByText(action === 'resolve' ? 'RESOLVED' : 'ACKNOWLEDGED')).toBeInTheDocument();
});

it('renders a dedicated missing-incident state for GET 404', async () => {
  fetchMock.mockResolvedValue(response({}, 404));
  show();
  expect(await screen.findByRole('heading', { name: 'Incident not found' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /Back to incidents/ })).toBeInTheDocument();
});

it('retries a general detail loading failure', async () => {
  fetchMock.mockResolvedValueOnce(response({}, 500)).mockResolvedValueOnce(response(incident));
  show();
  await userEvent.click(await screen.findByRole('button', { name: 'Retry' }));
  expect(await screen.findByRole('heading', { name: incident.title })).toBeInTheDocument();
});

it('handles stale acknowledge with 409 then GET latest RESOLVED without retrying PATCH', async () => {
  fetchMock.mockResolvedValueOnce(response(incident)).mockResolvedValueOnce(response({}, 409))
    .mockResolvedValueOnce(response(updated('RESOLVED')));
  show();
  await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }));
  expect(await screen.findByText('RESOLVED')).toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('latest state has been loaded');
  expect(fetchMock).toHaveBeenCalledTimes(3);
  expect(fetchMock.mock.calls[2][1]?.method).toBeUndefined();
  expect(String(fetchMock.mock.calls[2][0])).toContain('/incidents/' + incident.id);
  expect(patches()).toHaveLength(1);
  expect(screen.queryByRole('button', { name: /acknowledge|resolve/i })).not.toBeInTheDocument();
});

it('requires refresh when the conflict refetch fails', async () => {
  fetchMock.mockResolvedValueOnce(response(incident)).mockResolvedValueOnce(response({}, 409))
    .mockResolvedValueOnce(response({}, 500)).mockResolvedValueOnce(response(updated('ACKNOWLEDGED')));
  show();
  await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }));
  expect(await screen.findByText(/Refresh before trying another action/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Acknowledge' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Resolve' })).toBeDisabled();
  await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
  expect(await screen.findByText('ACKNOWLEDGED')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Resolve' })).toBeEnabled();
  expect(patches()).toHaveLength(1);
});

it('keeps detail visible after PATCH 500 and allows manual retry', async () => {
  fetchMock.mockResolvedValueOnce(response(incident)).mockResolvedValueOnce(response({}, 500))
    .mockResolvedValueOnce(response(updated('ACKNOWLEDGED')));
  show();
  await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Unable to acknowledge this incident');
  expect(screen.getByRole('heading', { name: incident.title })).toBeInTheDocument();
  expect(screen.getByText('OPEN')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Acknowledge' }));
  expect(await screen.findByText('ACKNOWLEDGED')).toBeInTheDocument();
  expect(patches()).toHaveLength(2);
});

it('refreshes the incident from the server', async () => {
  fetchMock.mockResolvedValueOnce(response(incident)).mockResolvedValueOnce(response(updated('RESOLVED')));
  show();
  await userEvent.click(await screen.findByRole('button', { name: 'Refresh' }));
  expect(await screen.findByText('RESOLVED')).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(patches()).toHaveLength(0);
});

it('refetches the incident list and Overview counts on return after a mutation', async () => {
  let current = incident;
  fetchMock.mockImplementation(async (input, options) => {
    const url = new URL(String(input));
    if (options?.method === 'PATCH') { current = updated('ACKNOWLEDGED'); return response(current); }
    if (url.pathname.endsWith(incident.id)) return response(current);
    const status = url.searchParams.get('status');
    return response(page(status && status !== current.status ? [] : url.pathname === '/events' ? [] : [current]));
  });
  show();
  await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }));
  await screen.findByText('ACKNOWLEDGED');
  await userEvent.click(screen.getByRole('link', { name: /Back to incidents/ }));
  expect(await screen.findByRole('link', { name: 'View incident ' + incident.id })).toBeInTheDocument();
  expect(screen.getByText('ACKNOWLEDGED')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('link', { name: /Overview/ }));
  expect(within(await screen.findByRole('region', { name: 'Acknowledged' })).getByText('1')).toBeInTheDocument();
  expect(within(screen.getByRole('region', { name: 'Open' })).getByText('0')).toBeInTheDocument();
});

it('provides graceful clipboard failure feedback', async () => {
  fetchMock.mockResolvedValue(response(incident));
  show();
  await screen.findByRole('heading', { name: incident.title });
  const user = userEvent.setup();
  vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValueOnce(new Error('Permission denied'));
  await user.click(screen.getByRole('button', { name: 'Copy incident id' }));
  expect(await screen.findByText(/Copy unavailable/)).toBeInTheDocument();
  expect(screen.getByText(incident.id)).toBeInTheDocument();
});