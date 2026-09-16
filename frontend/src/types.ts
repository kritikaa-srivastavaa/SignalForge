export interface Event {
  id: string;
  service: string;
  type: string;
  severity: string;
  message: string;
  timestamp: string;
  receivedAt: string;
}
export type IncidentStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';
export interface Incident {
  id: string;
  sourceEventId: string;
  service: string;
  type: string;
  severity: string;
  title: string;
  status: IncidentStatus;
  createdAt: string;
}
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}
export interface Filters {
  service: string;
  type: string;
  severity: string;
  status: IncidentStatus | '';
}
export interface PageQuery {
  page: number;
  size: number;
}
export type EventQuery = PageQuery & Partial<Pick<Filters, 'service' | 'type' | 'severity'>>;
export type IncidentQuery = EventQuery & { status?: IncidentStatus | '' };
