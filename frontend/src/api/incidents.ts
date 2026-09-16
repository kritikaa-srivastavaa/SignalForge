import { get } from './client';
import type { Incident, IncidentQuery, PageResponse } from '../types';

export function getIncidents({ page, size, service, type, severity, status }: IncidentQuery, signal?: AbortSignal) {
  return get<PageResponse<Incident>>('/incidents', { page, size, service, type, severity, status }, signal);
}
