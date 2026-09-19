// Same-origin proxy: session cookies and CSRF remain browser first-party state.
export async function onRequest({ request, env }) {
  const incoming = new URL(request.url);
  let backend;
  try {
    backend = new URL(env.BACKEND_ORIGIN);
    if (backend.protocol !== 'https:' || backend.pathname !== '/' || backend.username || backend.password) {
      throw new Error('Invalid backend origin');
    }
  } catch {
    return new Response('Backend is not configured', { status: 503 });
  }
  const path = incoming.pathname.slice('/api'.length);
  // Keep requests on the configured upstream; reject URL-normalization escapes.
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('\\')) {
    return new Response('Invalid API path', { status: 400 });
  }
  backend.pathname = path;
  backend.search = incoming.search;
  const headers = new Headers(request.headers);
  headers.delete('host');
  headers.delete('forwarded');
  headers.delete('x-forwarded-for');
  headers.delete('x-forwarded-host');
  headers.delete('x-forwarded-proto');
  headers.set('X-Forwarded-Host', incoming.host);
  headers.set('X-Forwarded-Proto', 'https');
  // Retain the real browser Origin for restricted backend CORS; never invent it.
  try {
    const upstream = await fetch(backend, {
      method: request.method, headers,
      body: ['GET', 'HEAD'].includes(request.method) ? undefined : request.body,
      redirect: 'manual',
    });
    const response = new Response(upstream.body, upstream);
    response.headers.set('Cache-Control', 'no-store');
    return response;
  } catch {
    return new Response('Backend temporarily unavailable', { status: 502 });
  }
}
