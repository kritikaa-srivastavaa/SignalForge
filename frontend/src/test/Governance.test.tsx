import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { UserRole } from '../types';
import type { AccessRequest, AccessStatus } from '../api/governance';

const userId = '00000000-0000-0000-0000-000000000001';
const requestId = '00000000-0000-0000-0000-000000000002';
let role: UserRole;
let latest: AccessRequest | undefined;
let queue: AccessRequest[];
let failure: number | undefined;
const fetchMock = vi.fn<typeof fetch>();
const identity = () => ({ id: userId, email: 'current@example.com', displayName: 'Current user', role, createdAt: '2026-09-18T10:00:00Z' });
const request = (status: AccessStatus = 'PENDING'): AccessRequest => ({
  id: requestId, requesterId: 'target-user', requesterEmail: 'requester@example.com',
  requestedRole: 'OPERATOR', status, createdAt: '2026-09-18T10:00:00Z',
  reviewedAt: status === 'PENDING' ? null : '2026-09-18T11:00:00Z',
  reviewedBy: status === 'PENDING' ? null : 'admin-user', reviewReason: null,
});
const response = (value: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(value), { status });
const page = (content: unknown[], index = 0, total = content.length) => ({
  content, page: index, size: 20, totalElements: total, totalPages: Math.ceil(total / 20), first: index === 0, last: (index + 1) * 20 >= total,
});
beforeEach(() => {
  role = 'VIEWER'; latest = undefined; queue = [request()]; failure = undefined;
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (input, options) => {
    const url = new URL(String(input)); const path = url.pathname;
    if (path === '/auth/me') return response(identity());
    if (path === '/auth/csrf') return response({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' });
    if (path === '/access-requests/me') return latest ? response(latest) : response(null, 204);
    if (options?.method === 'POST' && path === '/access-requests') {
      if (failure) return response({}, failure);
      latest = { ...request(), requesterId: userId, requestedRole: role === 'NO_ACCESS' ? 'VIEWER' : 'OPERATOR' }; return response(latest, 201);
    }
    if (options?.method === 'PATCH') {
      if (failure) return response({}, failure);
      const saved = { ...queue[0], status: (path.endsWith('/approve') ? 'APPROVED' : 'REJECTED') as AccessStatus };
      queue = []; return response(saved);
    }
    if (path === '/access-requests/review') return response(page(queue.filter(r => r.requestedRole === role)));
    if (path === '/admin/access-requests') return response(page(queue));
    if (path === '/admin/audit') return response(page([{
      id: 'audit-' + (url.searchParams.get('page') ?? '0'), actorId: userId, actorEmail: 'admin@example.com',
      action: 'USER_ROLE_CHANGED', targetType: 'USER', targetId: 'target-user',
      timestamp: '2026-09-18T11:00:00Z', oldValue: 'VIEWER', newValue: 'OPERATOR',
    }], Number(url.searchParams.get('page') ?? 0), 21));
    return response(page([]));
  });
  vi.stubGlobal('fetch', fetchMock);
});
const show = (path = '/access') => render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
const mutations = () => fetchMock.mock.calls.filter(([, options]) => ['POST', 'PATCH'].includes(options?.method ?? ''));

it('eligible VIEWER sees read-only explanation and requests only OPERATOR through CSRF transport', async () => {
  show();
  // The first async render can be slow on the Windows/Docker development machine.
  await userEvent.click(await screen.findByRole('button', { name: 'Request Operator Access' }, { timeout: 5000 }));
  expect(await screen.findByText('Access request pending')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Request Operator Access' })).not.toBeInTheDocument();
  expect(mutations()).toHaveLength(1);
  expect(mutations()[0][1]?.body).toBeUndefined();
  expect(mutations()[0][1]?.credentials).toBe('include');
  expect(new Headers(mutations()[0][1]?.headers).get('X-CSRF-TOKEN')).toBe('csrf-test');
});
it('restores PENDING state from backend on page entry', async () => {
  latest = request(); show();
  expect(await screen.findByText('Access request pending')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Request Operator Access' })).not.toBeInTheDocument();
});
it('REJECTED state displays user-visible reason and permits a new request', async () => {
  latest = { ...request('REJECTED'), reviewReason: 'Please clarify your duties.' }; show();
  expect(await screen.findByText('Access request rejected')).toBeInTheDocument();
  expect(screen.getByText('Please clarify your duties.')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Request Operator Access' }));
  expect(await screen.findByText('Access request pending')).toBeInTheDocument();
});
it('refresh observes approval and updates existing session role', async () => {
  latest = request(); show(); await screen.findByText('Access request pending');
  role = 'OPERATOR'; latest = request('APPROVED');
  await userEvent.click(screen.getByRole('button', { name: /Refresh/ }));
  expect(await screen.findByText('You have operational access (OPERATOR).')).toBeInTheDocument();
  expect(screen.getByText('Access request approved')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Request Operator Access' })).not.toBeInTheDocument();
  expect(document.querySelector('.session-identity')).toHaveTextContent('OPERATOR');
});
it.each(['OPERATOR', 'ADMIN'] as const)('%s does not receive a request action', async value => {
  role = value; show();
  expect(await screen.findByText(`You have operational access (${value}).`)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Request Operator Access' })).not.toBeInTheDocument();
});
it('historical approval does not grant access after a manual demotion', async () => {
  latest = request('APPROVED'); show();
  expect(await screen.findByRole('button', { name: 'Request Operator Access' })).toBeEnabled();
  expect(screen.getByText(/historical approval does not override/)).toBeInTheDocument();
});
it('blocks duplicate submissions while creation is in flight', async () => {
  show(); const button = await screen.findByRole('button', { name: 'Request Operator Access' });
  const original = fetchMock.getMockImplementation()!;
  let finish!: (value: Response) => void;
  fetchMock.mockImplementation((input, options) => options?.method === 'POST' ? new Promise(resolve => { finish = resolve; }) : original(input, options));
  fireEvent.click(button); fireEvent.click(button);
  await waitFor(() => expect(mutations()).toHaveLength(1));
  expect(button).toBeDisabled();
  latest = request();
  await act(async () => { finish(response(latest, 201)); });
  expect(await screen.findByText('Access request pending')).toBeInTheDocument();
});
it('creation conflict refreshes authoritative state', async () => {
  show(); const button = await screen.findByRole('button', { name: 'Request Operator Access' });
  failure = 409; latest = request();
  await userEvent.click(button);
  expect(await screen.findByText('Access request pending')).toBeInTheDocument();
  expect(screen.getByRole('alert')).toHaveTextContent('already pending');
});
it.each(['/admin/access-requests', '/admin/audit'])('VIEWER direct %s route is forbidden without fetching data', async path => {
  show(path);
  expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  expect(fetchMock.mock.calls.some(([input]) => new URL(String(input)).pathname === path)).toBe(false);
  expect(screen.getByRole('link', { name: 'Access Requests' })).toHaveAttribute('href', '/access/review');
  expect(screen.queryByRole('link', { name: 'Audit Log' })).not.toBeInTheDocument();
});
it.each(['/admin/access-requests', '/admin/audit'])('OPERATOR direct %s route is forbidden', async path => {
  role = 'OPERATOR'; show(path);
  expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
});
it('ADMIN sees queue, status filtering and governance navigation', async () => {
  role = 'ADMIN'; show('/admin/access-requests');
  expect(await screen.findByRole('region', { name: 'requester@example.com' })).toHaveTextContent('PENDING');
  expect(screen.getByRole('link', { name: 'Audit Log' })).toHaveAttribute('href', '/admin/audit');
  await userEvent.selectOptions(screen.getByLabelText('Request status'), 'REJECTED');
  await waitFor(() => expect(fetchMock.mock.calls.some(([input]) => String(input).includes('status=REJECTED'))).toBe(true));
});
it('approval uses server response and refreshes queue', async () => {
  role = 'ADMIN'; show('/admin/access-requests');
  await userEvent.click(await screen.findByRole('button', { name: 'Approve' }));
  expect(await screen.findByText('Request for requester@example.com: APPROVED.')).toBeInTheDocument();
  expect(await screen.findByText('No access requests found.')).toBeInTheDocument();
});
it('rejection requires explicit confirmation and submits bounded reason', async () => {
  role = 'ADMIN'; show('/admin/access-requests');
  await userEvent.click(await screen.findByRole('button', { name: 'Reject' }));
  const reason = screen.getByLabelText('Reason (optional, visible to requester)');
  expect(reason).toHaveAttribute('maxlength', '300');
  await userEvent.type(reason, 'Please clarify duties.');
  expect(mutations()).toHaveLength(0);
  await userEvent.click(screen.getByRole('button', { name: 'Confirm rejection' }));
  expect(await screen.findByText('Request for requester@example.com: REJECTED.')).toBeInTheDocument();
  expect(JSON.parse(String(mutations()[0][1]?.body))).toEqual({ reason: 'Please clarify duties.' });
});
it('stale review 409 refreshes queue without claiming success', async () => {
  role = 'ADMIN'; failure = 409; show('/admin/access-requests');
  await userEvent.click(await screen.findByRole('button', { name: 'Approve' }));
  expect(await screen.findByText(/already reviewed.*your action was not applied/)).toBeInTheDocument();
});
it('review buttons stay disabled in flight and do not optimistically approve', async () => {
  role = 'ADMIN'; show('/admin/access-requests');
  const button = await screen.findByRole('button', { name: 'Approve' });
  const original = fetchMock.getMockImplementation()!;
  let finish!: (value: Response) => void;
  fetchMock.mockImplementation((input, options) => options?.method === 'PATCH' ? new Promise(resolve => { finish = resolve; }) : original(input, options));
  fireEvent.click(button); fireEvent.click(button);
  await waitFor(() => expect(mutations()).toHaveLength(1));
  expect(button).toBeDisabled(); expect(screen.getByRole('button', { name: 'Reject' })).toBeDisabled();
  expect(screen.getByRole('region', { name: 'requester@example.com' })).toHaveTextContent('PENDING');
  queue = [];
  await act(async () => { finish(response(request('APPROVED'))); });
  expect(await screen.findByText(/requester@example.com: APPROVED/)).toBeInTheDocument();
});
it('own historical request cannot be reviewed after promotion to ADMIN', async () => {
  role = 'ADMIN'; queue = [{ ...request(), requesterId: userId }]; show('/admin/access-requests');
  expect(await screen.findByText('Another administrator must review your request.')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
});
it('audit viewer renders deliberate fields with pagination and action filter', async () => {
  role = 'ADMIN'; show('/admin/audit');
  const table = await screen.findByRole('table');
  expect(within(table).getByText('admin@example.com')).toBeInTheDocument();
  expect(within(table).getByText('VIEWER → OPERATOR')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /Delete|Clear/ })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Next' }));
  expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument();
  await userEvent.selectOptions(screen.getByLabelText('Audit action'), 'USER_ROLE_CHANGED');
  expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument();
  expect(fetchMock.mock.calls.some(([input]) => String(input).includes('action=USER_ROLE_CHANGED'))).toBe(true);
});

it.each(['/', '/events', '/incidents', '/incidents/00000000-0000-0000-0000-000000000001', '/admin/users', '/access/review'])
  ('NO_ACCESS redirects %s to admission without operational reads', async path => {
    role = 'NO_ACCESS'; show(path);
    expect(await screen.findByRole('heading', { name: 'Viewer access required' }, { timeout: 5000 })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Request Viewer Access' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Events' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Incidents' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Access Requests' })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => /\/(events|incidents|admin|access-requests\/review)(\/|\?|$)/.test(new URL(String(url)).pathname))).toBe(false);
  });

it('NO_ACCESS requests VIEWER with server-owned fields and pending state', async () => {
  role = 'NO_ACCESS'; show();
  await userEvent.click(await screen.findByRole('button', { name: 'Request Viewer Access' }, { timeout: 5000 }));
  expect(await screen.findByText('Access request pending')).toBeInTheDocument();
  expect(latest?.requestedRole).toBe('VIEWER');
  expect(mutations()).toHaveLength(1);
  expect(mutations()[0][1]?.body).toBeUndefined();
  expect(new Headers(mutations()[0][1]?.headers).get('X-CSRF-TOKEN')).toBe('csrf-test');
});

it.each(['VIEWER', 'OPERATOR'] as const)('%s reviews only its group through non-admin APIs', async target => {
  role = target;
  queue = [{ ...request(), requestedRole: target }, { ...request(), id: 'other-request',
    requesterEmail: 'other@example.com', requestedRole: target === 'VIEWER' ? 'OPERATOR' : 'VIEWER' }];
  show('/access/review');
  const row = await screen.findByRole('region', { name: 'requester@example.com' }, { timeout: 5000 });
  expect(screen.queryByText('other@example.com')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Request status')).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Access Requests' })).toHaveAttribute('href', '/access/review');
  expect(screen.queryByRole('link', { name: 'Users' })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'Audit Log' })).not.toBeInTheDocument();
  await userEvent.click(within(row).getByRole('button', { name: 'Approve' }));
  await waitFor(() => expect(mutations()).toHaveLength(1));
  expect(String(mutations()[0][0])).toContain('/access-requests/' + requestId + '/approve');
  expect(String(mutations()[0][0])).not.toContain('/admin/');
  expect(new Headers(mutations()[0][1]?.headers).get('X-CSRF-TOKEN')).toBe('csrf-test');
});

it('NO_ACCESS refreshes its session presentation after VIEWER approval', async () => {
  role = 'NO_ACCESS'; latest = { ...request(), requestedRole: 'VIEWER' }; show();
  expect(await screen.findByText('Access request pending', {}, { timeout: 5000 })).toBeInTheDocument();
  role = 'VIEWER'; latest = { ...latest, status: 'APPROVED' };
  await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
  expect(await screen.findByRole('button', { name: 'Request Operator Access' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Events' })).toBeInTheDocument();
});

it('rejected VIEWER admission keeps NO_ACCESS and permits a new VIEWER request', async () => {
  role = 'NO_ACCESS'; latest = { ...request('REJECTED'), requestedRole: 'VIEWER' }; show();
  expect(await screen.findByText('Access request rejected', {}, { timeout: 5000 })).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Request Viewer Access' }));
  expect(await screen.findByText('Access request pending')).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'Events' })).not.toBeInTheDocument();
});
