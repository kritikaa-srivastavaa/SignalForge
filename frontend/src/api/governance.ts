import { get, post, patch, patchJson } from './client';
import type { PageResponse } from '../types';

export type AccessStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export interface AccessRequest {
  id: string; requesterId: string; requesterEmail: string; requestedRole: 'OPERATOR';
  status: AccessStatus; createdAt: string; reviewedAt: string | null;
  reviewedBy: string | null; reviewReason: string | null;
}
export const auditActions = ['ACCESS_REQUEST_CREATED', 'ACCESS_REQUEST_APPROVED', 'ACCESS_REQUEST_REJECTED',
  'USER_ROLE_CHANGED', 'INCIDENT_ACKNOWLEDGED', 'INCIDENT_RESOLVED'] as const;
export type AuditAction = typeof auditActions[number];
export interface AuditRecord {
  id: string; actorId: string; actorEmail: string; action: AuditAction;
  targetType: 'ACCESS_REQUEST' | 'USER' | 'INCIDENT'; targetId: string;
  timestamp: string; oldValue: string | null; newValue: string | null;
}
export const latestRequest = (signal?: AbortSignal) => get<AccessRequest | undefined>('/access-requests/me', {}, signal);
export const requestAccess = () => post<AccessRequest>('/access-requests');
export const getRequests = (status: AccessStatus | '', page: number, signal?: AbortSignal) =>
  get<PageResponse<AccessRequest>>('/admin/access-requests', { status, page, size: 20 }, signal);
export const approveRequest = (id: string) => patch<AccessRequest>(`/admin/access-requests/${id}/approve`);
export const rejectRequest = (id: string, reason: string) => patchJson<AccessRequest>(`/admin/access-requests/${id}/reject`, { reason });
export const getAudit = (action: AuditAction | '', page: number, signal?: AbortSignal) =>
  get<PageResponse<AuditRecord>>('/admin/audit', { action, page, size: 20 }, signal);
