import { act, fireEvent, render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { User, UserRole } from '../types';

const userId = '00000000-0000-0000-0000-000000000001';
const targetId = '00000000-0000-0000-0000-000000000002';
const incidentId = '00000000-0000-0000-0000-000000000003';
let role: UserRole;
let targetRole: UserRole;
let failure: number | undefined;
const fetchMock = vi.fn<typeof fetch>();
const identity = (): User => ({ id: userId, email: 'admin@example.com', displayName: 'Current user', role, createdAt: '2026-09-17T10:00:00Z' });
const target = (): User => ({ id: targetId, email: 'target@example.com', displayName: 'Target user', role: targetRole, createdAt: '2026-09-17T10:00:00Z' });
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
const page = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true };
beforeEach(() => {
  role = 'VIEWER'; targetRole = 'VIEWER'; failure = undefined;
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (input, options) => {
    const path = new URL(String(input)).pathname;
    if (path === '/auth/me') return response(identity());
    if (path === '/auth/csrf') return response({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' });
    if (path === '/admin/users') return response([identity(), target()]);
    if (path.endsWith('/role')) {
      if (failure) return response({}, failure);
      const selected = JSON.parse(String(options?.body)).role as UserRole;
      if (path.includes(userId)) { role = selected; return response(identity()); }
      targetRole = selected; return response(target());
    }
    if (path === '/incidents/' + incidentId) return response({
      id: incidentId, sourceEventId: 'event-1', service: 'Test service', type: 'ERROR',
      severity: 'HIGH', title: 'Test incident', status: 'OPEN', createdAt: '2026-09-17T10:00:00Z',
    });
    return response(page);
  });
  vi.stubGlobal('fetch', fetchMock);
});
const show = (path = '/incidents/' + incidentId) => render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
const mutationCalls = () => fetchMock.mock.calls.filter(([, options]) => options?.method === 'PATCH');
async function selectRole(email: string, selected: UserRole) {
  const row = await screen.findByRole('region', { name: email });
  await userEvent.selectOptions(within(row).getByRole('combobox'), selected);
  return row;
}

it('restores the VIEWER role and renders explicit read-only incident detail', async () => {
  show(); await screen.findByRole('heading', { name: 'Test incident' });
  expect(screen.getByText(/Read-only access/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Resolve' })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
  expect(screen.getByText(/VIEWER/)).toBeInTheDocument();
});
it.each(['OPERATOR', 'ADMIN'] as const)('%s receives lifecycle controls', async selected => {
  role = selected; show();
  expect(await screen.findByRole('button', { name: 'Acknowledge' })).toBeEnabled();
  expect(screen.getByRole('button', { name: 'Resolve' })).toBeEnabled();
  expect(screen.queryByText(/Read-only access/)).not.toBeInTheDocument();
  expect(Boolean(screen.queryByRole('link', { name: 'Admin' }))).toBe(selected === 'ADMIN');
});
it.each(['VIEWER', 'OPERATOR'] as const)('%s direct admin route is forbidden without fetching user data', async selected => {
  role = selected; show('/admin/users');
  expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  expect(screen.getByRole('alert')).toHaveTextContent("You are signed in, but you don't have permission");
  expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/admin/users'))).toBe(false);
});
it('ADMIN loads identities and current roles', async () => {
  role = 'ADMIN'; show('/admin/users');
  expect(await screen.findByRole('region', { name: 'target@example.com' })).toHaveTextContent('Current role: VIEWER');
  expect(screen.getByRole('link', { name: 'Admin' })).toHaveAttribute('href', '/admin/users');
  expect(screen.getByRole('heading', { name: 'Users and roles' })).toBeInTheDocument();
});
it('changes a role using server data and credentialed CSRF-protected PATCH', async () => {
  role = 'ADMIN'; show('/admin/users');
  const row = await selectRole('target@example.com', 'OPERATOR');
  await userEvent.click(within(row).getByRole('button', { name: 'Save role' }));
  expect(await within(row).findByRole('status')).toHaveTextContent('Role updated to OPERATOR');
  expect(row).toHaveTextContent('Current role: OPERATOR');
  expect(mutationCalls()).toHaveLength(1);
  expect(JSON.parse(String(mutationCalls()[0][1]?.body))).toEqual({ role: 'OPERATOR' });
  expect(mutationCalls()[0][1]?.credentials).toBe('include');
  expect(new Headers(mutationCalls()[0][1]?.headers).get('X-CSRF-TOKEN')).toBe('csrf-test');
});
it('prevents duplicate role submissions and does not update optimistically', async () => {
  role = 'ADMIN'; show('/admin/users');
  const row = await selectRole('target@example.com', 'OPERATOR');
  const original = fetchMock.getMockImplementation()!;
  let finish!: (value: Response) => void;
  fetchMock.mockImplementation((input, options) => options?.method === 'PATCH'
    ? new Promise(resolve => { finish = resolve; }) : original(input, options));
  const form = within(row).getByRole('button', { name: 'Save role' }).closest('form')!;
  fireEvent.submit(form); fireEvent.submit(form);
  await waitFor(() => expect(mutationCalls()).toHaveLength(1));
  expect(within(row).getByRole('button', { name: 'Saving…' })).toBeDisabled();
  expect(within(row).getByRole('combobox')).toBeDisabled();
  expect(row).toHaveTextContent('Current role: VIEWER');
  await act(async () => finish(response({ ...target(), role: 'OPERATOR' })));
  expect(await within(row).findByRole('status')).toHaveTextContent('Role updated to OPERATOR');
});
it('shows role-save errors and retains the current server role', async () => {
  role = 'ADMIN'; failure = 500; show('/admin/users');
  const row = await selectRole('target@example.com', 'OPERATOR');
  await userEvent.click(within(row).getByRole('button', { name: 'Save role' }));
  expect(await within(row).findByRole('alert')).toHaveTextContent('HTTP 500');
  expect(row).toHaveTextContent('Current role: VIEWER');
  expect(within(row).getByRole('button', { name: 'Save role' })).toBeEnabled();
});
it('explains last-admin 409 without removing admin controls', async () => {
  role = 'ADMIN'; failure = 409; show('/admin/users');
  const row = await selectRole('admin@example.com', 'VIEWER');
  await userEvent.click(within(row).getByRole('button', { name: 'Save role' }));
  expect(await within(row).findByRole('alert')).toHaveTextContent('last administrator cannot be demoted');
  expect(screen.getByRole('link', { name: 'Admin' })).toBeInTheDocument();
  expect(row).toHaveTextContent('Current role: ADMIN');
});
it('immediately updates route and navigation after permitted self-demotion', async () => {
  role = 'ADMIN'; show('/admin/users');
  const row = await selectRole('admin@example.com', 'VIEWER');
  await userEvent.click(within(row).getByRole('button', { name: 'Save role' }));
  expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
  expect(screen.queryByRole('region', { name: 'target@example.com' })).not.toBeInTheDocument();
});
it('refreshes session permissions after a backend authorization rejection', async () => {
  role = 'ADMIN'; show('/admin/users');
  const row = await selectRole('target@example.com', 'OPERATOR');
  const original = fetchMock.getMockImplementation()!;
  fetchMock.mockImplementation((input, options) => {
    if (options?.method === 'PATCH') {
      role = 'VIEWER'; return Promise.resolve(response({ code: 'FORBIDDEN' }, 403));
    }
    return original(input, options);
  });
  await userEvent.click(within(row).getByRole('button', { name: 'Save role' }));
  expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
});
it('keeps a CSRF rejection distinct from a role rejection', async () => {
  role = 'ADMIN'; show('/admin/users');
  const row = await selectRole('target@example.com', 'OPERATOR');
  const original = fetchMock.getMockImplementation()!;
  fetchMock.mockImplementation((input, options) => options?.method === 'PATCH'
    ? Promise.resolve(response({ code: 'CSRF_INVALID' }, 403)) : original(input, options));
  await userEvent.click(within(row).getByRole('button', { name: 'Save role' }));
  expect(await within(row).findByRole('alert')).toHaveTextContent('security token expired');
  expect(screen.getByRole('link', { name: 'Admin' })).toBeInTheDocument();
});
