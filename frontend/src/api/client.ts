const baseUrl = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');

export class ApiError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`The server returned HTTP ${status}. Please retry.`);
    this.name = 'ApiError';
    this.status = status;
  }
}
export async function get<T>(
  path: string,
  parameters: Record<string, string | number | undefined>,
  signal?: AbortSignal,
): Promise<T> {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(parameters)) {
    if (value !== undefined && String(value).trim() !== '') {
      query.set(key, String(value).trim());
    }
  }
  const response = await fetch(`${baseUrl}${path}?${query}`, { signal });
  if (!response.ok) throw new ApiError(response.status);
  return response.json() as Promise<T>;
}
