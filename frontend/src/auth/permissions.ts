import type { User } from '../types';
// Presentation only; Spring Security enforces the actual policy.
export const canManageIncidents = (user: User | null) => user?.role === 'OPERATOR' || user?.role === 'ADMIN';
export const canManageUsers = (user: User | null) => user?.role === 'ADMIN';
