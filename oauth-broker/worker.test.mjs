import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handle } from './worker.mjs';
const env = { PUBLIC_HOST: 'auth.example.com', GOOGLE_CLIENT_ID: 'fixture.apps.googleusercontent.com', GOOGLE_CLIENT_SECRET: 'fake-test-only', ANDROID_CERTIFICATES_SHA256: Array(32).fill('AB').join(':') };
const code = 'test-code'; const code_verifier = 'v'.repeat(43);
const request = (values = { code, code_verifier }) => new Request('https://auth.example.com/oauth/exchange', { method: 'POST', body: new URLSearchParams(values) });
test('exchanges a code using the secret only upstream, never returns refresh/id tokens', async () => {
  const response = await handle(request(), env, async (url, init) => {
    assert.equal(url, 'https://oauth2.googleapis.com/token'); assert.equal(init.redirect, 'error');
    assert.equal(init.body.get('client_secret'), env.GOOGLE_CLIENT_SECRET); assert.equal(init.body.get('code_verifier'), code_verifier);
    assert.equal(init.body.get('redirect_uri'), 'https://auth.example.com/oauth/callback');
    return Response.json({ access_token: 'short-lived-fixture', token_type: 'Bearer', expires_in: 3600, scope: 'https://www.googleapis.com/auth/youtube.readonly', refresh_token: 'must-discard', id_token: 'no-account-profile' });
  });
  assert.equal(response.status, 200); assert.equal(response.headers.get('Cache-Control'), 'no-store');
  const body = await response.text(); assert.ok(!body.includes('must-discard')); assert.ok(!body.includes('fake-test-only')); assert.ok(!body.includes('no-account-profile'));
});
test('rejects injected client/redirect, duplicate codes, missing PKCE and oversized bodies without contacting Google', async () => {
  const never = () => { throw new Error('must not fetch'); };
  for (const values of [{ code }, { code, code_verifier, redirect_uri: 'https://evil.example' }, { code: 'a'.repeat(9000), code_verifier }, [['code', code], ['code', code], ['code_verifier', code_verifier]]]) {
    assert.notEqual((await handle(request(values), env, never)).status, 200);
  }
});
test('provider errors and missing permission are sanitized', async () => {
  const denied = await handle(request(), env, async () => new Response('provider detail including sensitive text', { status: 400 }));
  assert.equal(denied.status, 401); assert.ok(!(await denied.text()).includes('sensitive'));
  const missing = await handle(request(), env, async () => Response.json({ access_token: 'fixture', token_type: 'Bearer', scope: 'email' }));
  assert.equal(missing.status, 403); assert.ok(!(await missing.text()).includes('fixture'));
});
test('serves only configured Android App Link certificates; callback never echoes authorization data', async () => {
  const links = await handle(new Request('https://auth.example.com/.well-known/assetlinks.json'), env);
  assert.equal((await links.json())[0].target.package_name, 'org.sonora.music');
  const callback = await handle(new Request('https://auth.example.com/oauth/callback?code=private-code&state=private-state'), env);
  assert.ok(!(await callback.text()).includes('private-'));
  assert.equal((await handle(request(), {})).status, 503);
});
test('rejects browser origins and wrong hosts', async () => {
  const browser = request(); browser.headers.set('Origin', 'https://evil.example');
  assert.equal((await handle(browser, env)).status, 400);
  assert.equal((await handle(new Request('https://evil.example/oauth/exchange'), env)).status, 400);
});
test('Cloudflare entrypoint does not confuse the execution context with fetch', async () => {
  const worker = (await import('./worker.mjs')).default;
  const original = globalThis.fetch;
  globalThis.fetch = async () => Response.json({ access_token: 'entry-fixture', token_type: 'Bearer', scope: 'https://www.googleapis.com/auth/youtube.readonly' });
  try { assert.equal((await worker.fetch(request(), env, { waitUntil() {} })).status, 200); }
  finally { globalThis.fetch = original; }
});
