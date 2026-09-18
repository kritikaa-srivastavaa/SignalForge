import { get } from './client';
import type { Event, EventQuery, PageResponse } from '../types';

export function getEvents({ page, size, service, type, severity }: EventQuery, signal?: AbortSignal) {
  return get<PageResponse<Event>>('/events', { page, size, service, type, severity }, signal);
}
