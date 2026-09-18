import type { ReactNode } from 'react';
// These business tests run inside an authenticated boundary; Auth.test covers real state transitions.
vi.mock('../auth/AuthProvider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: () => ({
    user: { id: 'test-user', email: 'test@example.com', displayName: 'Test User', role: 'OPERATOR' },
    loading: false, error: null, logout: vi.fn(), refreshUser: vi.fn(),
  }),
}));
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { getEvents } from '../api/events';
import { ApiError } from '../api/client';
import { formatTime } from '../utils/format';
import type { Event, Incident, PageResponse } from '../types';

const event: Event = {
  id: 'event-1', service: 'payment-service', type: 'API_ERROR', severity: 'HIGH',
  message: 'Payment gateway timed out', timestamp: '2026-09-16T10:30:00Z', receivedAt: '2026-09-16T10:30:01Z',
};
const incident: Incident = {
  id: 'incident-1', sourceEventId: event.id, service: event.service, type: event.type,
  severity: 'HIGH', status: 'OPEN', title: 'Event spike detected', createdAt: event.receivedAt,
};
function page<T>(content: T[], totalElements = content.length, number = 0, size = 20): PageResponse<T> {
  const totalPages = Math.ceil(totalElements / size);
  return { content, totalElements, page: number, size, totalPages, first: number === 0, last: number >= totalPages - 1 };
}
const fetchMock = vi.fn<typeof fetch>();
function respond(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
}
function requests() {
  return fetchMock.mock.calls.map(call => new URL(String(call[0])));
}
function show(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
}
beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', (input: RequestInfo | URL, options?: RequestInit) =>
  String(input).endsWith('/auth/csrf')
    ? Promise.resolve(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' })))
    : fetchMock(input, options)); });

describe('Overview', () => {
  it('renders actual page totals and recent rows with five bounded requests', async () => {
    fetchMock.mockImplementation(async input => {
      const url = new URL(String(input));
      if (url.pathname === '/events') return respond(page([event], 42, 0, 5));
      const status = url.searchParams.get('status');
      if (status) return respond(page([], { OPEN: 7, ACKNOWLEDGED: 3, RESOLVED: 11 }[status], 0, 1));
      return respond(page([incident], 21, 0, 5));
    });
    show('/');
    await screen.findByRole('region', { name: 'Total Events' });
    for (const [label, count] of [['Total Events', '42'], ['Total Incidents', '21'], ['Open', '7'], ['Acknowledged', '3'], ['Resolved', '11']]) {
      expect(within(screen.getByRole('region', { name: label })).getByText(count)).toBeInTheDocument();
    }
    expect(within(screen.getByRole('region', { name: 'Recent Events' })).getByText('payment-service')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Recent Incidents' })).getByText('OPEN')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(5);
    expect(requests().every(url => ['1', '5'].includes(url.searchParams.get('size') || ''))).toBe(true);
    expect(screen.getByRole('link', { name: /View all incidents/ })).toHaveAttribute('href', '/incidents');
  });

  it('shows loading instead of fabricated zero counts', () => {
    fetchMock.mockImplementation(() => new Promise<Response>(() => {}));
    show('/');
    expect(screen.getByRole('status')).toHaveTextContent('Loading workspace data');
    expect(screen.queryByRole('region', { name: 'Total Events' })).not.toBeInTheDocument();
  });

  it('handles a failed overview request and retries', async () => {
    fetchMock.mockResolvedValue(respond({}, 500));
    show('/');
    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load overview');
    fetchMock.mockImplementation(async () => respond(page([])));
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('region', { name: 'Total Events' })).toHaveTextContent('0');
  });
});

describe('Events', () => {
  it('renders all event fields and keeps full messages accessible', async () => {
    fetchMock.mockResolvedValue(respond(page([event])));
    show('/events');
    expect(await screen.findByText(event.message)).toHaveAttribute('title', event.message);
    expect(screen.getByText(event.service)).toBeInTheDocument();
    expect(screen.getByText(event.type)).toBeInTheDocument();
    expect(screen.getByText('HIGH')).toBeInTheDocument();
    expect(screen.getByText(formatTime(event.timestamp))).toBeInTheDocument();
    expect(screen.getByText(formatTime(event.receivedAt))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  it('applies safely encoded filters only on submit and clears them', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation(async () => respond(page([event])));
    show('/events');
    await screen.findByText(event.message);
    await user.type(screen.getByLabelText('Service'), 'payments & billing');
    await user.type(screen.getByLabelText('Type'), 'API/ERROR');
    await user.type(screen.getByLabelText('Severity'), 'CUSTOM');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Apply Filters' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    const query = requests().at(-1)!.searchParams;
    expect(Object.fromEntries(query)).toEqual({ page: '0', size: '20', service: 'payments & billing', type: 'API/ERROR', severity: 'CUSTOM' });
    await user.click(screen.getByRole('button', { name: 'Clear Filters' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    expect(Object.fromEntries(requests().at(-1)!.searchParams)).toEqual({ page: '0', size: '20' });
    expect(screen.getByLabelText('Service')).toHaveValue('');
  });

  it('requests the next backend page and resets pagination on filter apply', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation(async input => {
      const number = Number(new URL(String(input)).searchParams.get('page'));
      return respond(page([event], 21, number));
    });
    show('/events');
    await screen.findByText('Page 1 of 2');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Page 2 of 2');
    expect(requests().at(-1)!.searchParams.get('page')).toBe('1');
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    await user.type(screen.getByLabelText('Service'), 'payment-service');
    await user.click(screen.getByRole('button', { name: 'Apply Filters' }));
    await screen.findByText('Page 1 of 2');
    expect(requests().at(-1)!.searchParams.get('page')).toBe('0');
  });

  it('renders an empty filtered result and disables pagination', async () => {
    fetchMock.mockResolvedValue(respond(page([])));
    show('/events');
    expect(await screen.findByText('No events found.')).toBeInTheDocument();
    expect(screen.getByText('Page 0 of 0')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  it('shows network errors and retries successfully', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValueOnce(respond(page([event])));
    show('/events');
    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load events');
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText(event.message)).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

describe('Incidents', () => {
  it('renders all lifecycle statuses without mutation controls', async () => {
    fetchMock.mockResolvedValue(respond(page([
      incident, { ...incident, id: '2', status: 'ACKNOWLEDGED' }, { ...incident, id: '3', status: 'RESOLVED' },
    ])));
    show('/incidents');
    expect(await screen.findByText('OPEN')).toBeInTheDocument();
    expect(screen.getByText('ACKNOWLEDGED')).toBeInTheDocument();
    expect(screen.getByText('RESOLVED')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /acknowledge|resolve/i })).not.toBeInTheDocument();
  });

  it('sends the selected status, paginates on the server, and handles empty results', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation(async input => {
      const query = new URL(String(input)).searchParams;
      return respond(query.get('status') ? page([]) : page([incident], 21, Number(query.get('page'))));
    });
    show('/incidents');
    await screen.findByText('Page 1 of 2');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Page 2 of 2');
    expect(requests().at(-1)!.pathname).toBe('/incidents');
    expect(requests().at(-1)!.searchParams.get('page')).toBe('1');
    await user.selectOptions(screen.getByLabelText('Status'), 'RESOLVED');
    await user.click(screen.getByRole('button', { name: 'Apply Filters' }));
    expect(await screen.findByText('No incidents found.')).toBeInTheDocument();
    expect(requests().at(-1)!.searchParams.get('status')).toBe('RESOLVED');
    expect(requests().at(-1)!.searchParams.get('page')).toBe('0');
  });
});

it.each([400, 404, 500])('rejects HTTP %s rather than treating it as an empty page', async status => {
  fetchMock.mockResolvedValue(respond({}, status));
  await expect(getEvents({ page: 0, size: 20 })).rejects.toEqual(new ApiError(status));
});

it('handles unknown routes without requesting data', () => {
  show('/not-a-page');
  expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  expect(fetchMock).not.toHaveBeenCalled();
});

it('formats invalid timestamps safely', () => {
  expect(formatTime('invalid')).toBe('Unavailable');
});
