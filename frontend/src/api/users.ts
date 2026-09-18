import { get, patchJson } from './client';
import type { User, UserRole } from '../types';
export const getUsers = (signal?: AbortSignal) => get<User[]>('/admin/users', {}, signal);
export const changeUserRole = (id: string, role: UserRole) =>
  patchJson<User>(`/admin/users/${encodeURIComponent(id)}/role`, { role });
