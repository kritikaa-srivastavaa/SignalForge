const baseUrl = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  constructor(status: number, code?: string) {
    const messages: Record<number, string> = {
      400: 'Check the supplied values and try again.',
      401: 'Please log in to continue.',
      403: code === 'CSRF_INVALID' ? 'Your security token expired. Please try again.' : 'You do not have permission to perform this action.',
      409: 'The request conflicts with the current record.',
    };
    super(messages[status] || `The server returned HTTP ${status}. Please retry.`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

async function request<T>(path: string, options: RequestInit): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, { ...options, credentials: 'include' });
  if (!response.ok) {
    // A server-side expiry immediately removes protected UI. Login failures stay on the form.
    if (response.status === 401 && !path.startsWith('/auth/')) {
      window.dispatchEvent(new Event('signalforge:unauthenticated'));
    }
    let code: string | undefined;
    if (response.status === 403) {
      const body = await response.json().catch(() => ({}));
      if (body.code === 'FORBIDDEN' || body.code === 'CSRF_INVALID') code = body.code;
      if (code === 'FORBIDDEN') window.dispatchEvent(new Event('signalforge:permissions-changed'));
    }
    throw new ApiError(response.status, code);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export function get<T>(
  path: string,
  parameters: Record<string, string | number | undefined> = {},
  signal?: AbortSignal,
): Promise<T> {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(parameters)) {
    if (value !== undefined && String(value).trim() !== '') query.set(key, String(value).trim());
  }
  return request<T>(query.size ? `${path}?${query}` : path, { signal });
}

async function mutate<T>(path: string, method: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  // Fetch afresh so login/logout token rotation and backend restarts cannot leave a cached token.
  const csrf = await get<{ headerName: string; token: string }>('/auth/csrf', {}, signal);
  const headers: Record<string, string> = { [csrf.headerName]: csrf.token };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  return request<T>(path, { method, signal, headers, body: body === undefined ? undefined : JSON.stringify(body) });
}

export function post<T>(path: string, body?: unknown): Promise<T> {
  return mutate<T>(path, 'POST', body);
}

export function patch<T>(path: string, signal?: AbortSignal): Promise<T> {
  return mutate<T>(path, 'PATCH', undefined, signal);
}

export function patchJson<T>(path: string, body: unknown): Promise<T> {
  return mutate<T>(path, 'PATCH', body);
}