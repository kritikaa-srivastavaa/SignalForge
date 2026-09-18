import type { ReactNode } from 'react';
import { vi, it, expect } from 'vitest';
vi.mock('../auth/AuthProvider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: () => ({ user: { id: 'test-user', email: 'test@example.com', displayName: 'Test User', role: 'VIEWER' }, loading: false, error: null, logout: vi.fn(), refreshUser: vi.fn() }),
}));
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { App } from '../App';

it('opens navigation, focuses its close button and restores focus on Escape', async () => {
  render(<MemoryRouter initialEntries={['/unknown']}><App /></MemoryRouter>);
  const menu = screen.getByRole('button', { name: 'Menu' });
  await userEvent.click(menu);
  expect(menu).toHaveAttribute('aria-expanded', 'true');
  expect(screen.getByRole('button', { name: 'Close navigation' })).toHaveFocus();
  await userEvent.keyboard('{Escape}');
  expect(menu).toHaveAttribute('aria-expanded', 'false');
  expect(menu).toHaveFocus();
});
it('traps keyboard focus within open navigation and closes on destination change', async () => {
  render(<MemoryRouter initialEntries={['/unknown']}><App /></MemoryRouter>);
  await userEvent.click(screen.getByRole('button', { name: 'Menu' }));
  await userEvent.keyboard('{Shift>}{Tab}{/Shift}');
  expect(screen.getByRole('link', { name: 'Access Requests' })).toHaveFocus();
  await userEvent.tab();
  expect(screen.getByRole('button', { name: 'Close navigation' })).toHaveFocus();
  await userEvent.click(screen.getByRole('link', { name: 'Overview' }));
  expect(screen.getByRole('button', { name: 'Menu' })).toHaveAttribute('aria-expanded', 'false');
  expect(document.getElementById('main')).toHaveFocus();
});
