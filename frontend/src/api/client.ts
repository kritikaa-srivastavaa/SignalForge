const baseUrl = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');

export class ApiError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`The server returned HTTP ${status}. Please retry.`);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function request<T>(path: string, options: RequestInit): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, options);
  if (!response.ok) throw new ApiError(response.status);
  return response.json() as Promise<T>;
}

export function get<T>(
  path: string,
  parameters: Record<string, string | number | undefined> = {},
  signal?: AbortSignal,
): Promise<T> {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(parameters)) {
    if (value !== undefined && String(value).trim() !== '') {
      query.set(key, String(value).trim());
    }
  }
  return request<T>(query.size ? `${path}?${query}` : path, { signal });
}

export function patch<T>(path: string, signal?: AbortSignal): Promise<T> {
  // Lifecycle endpoints accept no request body.
  return request<T>(path, { method: 'PATCH', signal });
}