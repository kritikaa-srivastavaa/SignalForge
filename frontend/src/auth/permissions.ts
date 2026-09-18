import type { User } from '../types';
// Presentation only; Spring Security enforces the actual policy.
export const canManageIncidents = (user: User | null) => user?.role === 'OPERATOR' || user?.role === 'ADMIN';
export const canManageUsers = (user: User | null) => user?.role === 'ADMIN';

export const canReadOperations = (user: User | null) => !!user && user.role !== 'NO_ACCESS';
export const canReviewGroup = (user: User | null) => user?.role === 'VIEWER' || user?.role === 'OPERATOR';
