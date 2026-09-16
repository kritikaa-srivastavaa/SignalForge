import { get, patch } from './client';
import type { Incident, IncidentQuery, PageResponse } from '../types';

export function getIncidents({ page, size, service, type, severity, status }: IncidentQuery, signal?: AbortSignal) {
  return get<PageResponse<Incident>>('/incidents', { page, size, service, type, severity, status }, signal);
}

export function getIncident(id: string, signal?: AbortSignal) {
  return get<Incident>(`/incidents/${encodeURIComponent(id)}`, {}, signal);
}

export function acknowledgeIncident(id: string, signal?: AbortSignal) {
  return patch<Incident>(`/incidents/${encodeURIComponent(id)}/acknowledge`, signal);
}

export function resolveIncident(id: string, signal?: AbortSignal) {
  return patch<Incident>(`/incidents/${encodeURIComponent(id)}/resolve`, signal);
}