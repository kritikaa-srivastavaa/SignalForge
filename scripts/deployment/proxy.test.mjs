import test from 'node:test';
import assert from 'node:assert/strict';
import { onRequest } from '../../frontend/functions/api/[[path]].js';

test('fails closed without a secure configured backend', async () => {
  for (const origin of [undefined, 'http://localhost:8080', 'https://user:pass@example.invalid']) {
    const response = await onRequest({request: new Request('https://demo.pages.dev/api/events'), env: {BACKEND_ORIGIN: origin}});
    assert.equal(response.status, 503);
  }
});

test('preserves mutation, query, cookies, CSRF and browser origin; strips spoofed forwarding', async () => {
  const original = globalThis.fetch;
  let called = false;
  globalThis.fetch = async (url, options) => {
    called = true;
    assert.equal(url.href, 'https://api.onrender.com/events?size=2');
    assert.equal(options.method, 'POST');
    assert.equal(options.headers.get('Cookie'), 'JSESSIONID=synthetic');
    assert.equal(options.headers.get('X-CSRF-TOKEN'), 'test-token');
    assert.equal(options.headers.get('Origin'), 'https://demo.pages.dev');
    assert.equal(options.headers.get('X-Forwarded-Host'), 'demo.pages.dev');
    assert.equal(options.headers.get('X-Forwarded-Proto'), 'https');
    assert.equal(options.headers.get('X-Forwarded-For'), null);
    assert.equal(options.redirect, 'manual');
    assert.equal(await new Response(options.body).text(), '{"service":"demo"}');
    return new Response('{"id":"test"}', {status:201,headers:{'Set-Cookie':'JSESSIONID=new; Path=/; HttpOnly; Secure; SameSite=Lax'}});
  };
  try {
    const request = new Request('https://demo.pages.dev/api/events?size=2', {method:'POST',body:'{"service":"demo"}',headers:{Cookie:'JSESSIONID=synthetic','X-CSRF-TOKEN':'test-token',Origin:'https://demo.pages.dev','X-Forwarded-For':'spoofed'}});
    const response = await onRequest({request,env:{BACKEND_ORIGIN:'https://api.onrender.com'}});
    assert.equal(response.status,201);
    assert.equal(response.headers.get('Cache-Control'),'no-store');
    assert.match(response.headers.get('Set-Cookie'),/Secure/);
    assert.equal(called,true);
  } finally { globalThis.fetch = original; }
});

test('path cannot select a different upstream', async () => {
  const response = await onRequest({request:new Request('https://demo.pages.dev/api//evil.invalid/events'),env:{BACKEND_ORIGIN:'https://api.onrender.com'}});
  assert.equal(response.status,400);
});

test('upstream failure is a safe 502 without exception details', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => {throw new Error('private provider detail');};
  try {
    const response = await onRequest({request:new Request('https://demo.pages.dev/api/auth/me'),env:{BACKEND_ORIGIN:'https://api.onrender.com'}});
    assert.equal(response.status,502);
    assert.equal(await response.text(),'Backend temporarily unavailable');
  } finally {globalThis.fetch=original;}
});
