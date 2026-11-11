// SPDX-License-Identifier: GPL-3.0-or-later
// Optional, stateless Google OAuth code exchange. No logging, cookies, storage or refresh endpoint.
const scope = 'https://www.googleapis.com/auth/youtube.readonly';
const headers = { 'Cache-Control': 'no-store', 'Pragma': 'no-cache', 'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff', 'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'" };
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { ...headers, 'Content-Type': 'application/json' } });
async function boundedText(body, limit) {
  if (!body) return '';
  const reader = body.getReader(); const chunks = []; let size = 0;
  try {
    while (true) { const { value, done } = await reader.read(); if (done) break;
      size += value.byteLength; if (size > limit) { await reader.cancel(); throw new Error('oversize'); } chunks.push(value); }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let at = 0; for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.length; }
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
}
export async function handle(request, env, fetchGoogle = fetch) {
  const url = new URL(request.url);
  const configured = /^[a-z0-9]+(?:[.-][a-z0-9]+)*\.[a-z]{2,}$/.test(env.PUBLIC_HOST || '') &&
    /^[A-Za-z0-9-]+\.apps\.googleusercontent\.com$/.test(env.GOOGLE_CLIENT_ID || '') && env.GOOGLE_CLIENT_SECRET;
  if (!configured) return json({ error: 'broker_not_configured' }, 503);
  if (url.protocol !== 'https:' || url.host !== env.PUBLIC_HOST) return json({ error: 'wrong_origin' }, 400);
  if (url.pathname === '/.well-known/assetlinks.json' && request.method === 'GET') {
    const pins = (env.ANDROID_CERTIFICATES_SHA256 || '').split(',').map(s => s.trim().toUpperCase()).filter(s => /^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$/.test(s));
    if (!pins.length) return json({ error: 'missing_signing_certificate' }, 503);
    return json([{ relation: ['delegate_permission/common.handle_all_urls'], target: { namespace: 'android_app', package_name: 'org.sonora.music', sha256_cert_fingerprints: pins } }]);
  }
  if (url.pathname === '/oauth/callback' && request.method === 'GET') {
    // Android should intercept this verified App Link. Never echo/store the authorization code/state.
    return new Response('Return to Sonora. If Android did not open the app, cancel the import, enable supported links for Sonora in Android Settings and authorize again.', { status: 200, headers: { ...headers, 'Content-Type': 'text/plain; charset=utf-8' } });
  }
  if (url.pathname !== '/oauth/exchange' || request.method !== 'POST') return json({ error: 'not_found' }, 404);
  // Native requests only; no browser CORS bridge, redirect override or arbitrary client selection.
  if (request.headers.has('Origin') || !request.headers.get('Content-Type')?.startsWith('application/x-www-form-urlencoded')) return json({ error: 'invalid_request' }, 400);
  try {
    const values = new URLSearchParams(await boundedText(request.body, 8192));
    if ([...values.keys()].some(key => !['code', 'code_verifier'].includes(key)) || values.getAll('code').length !== 1 || values.getAll('code_verifier').length !== 1) return json({ error: 'invalid_request' }, 400);
    const code = values.get('code'); const verifier = values.get('code_verifier');
    if (!code || code.length > 4096 || !/^[A-Za-z0-9._~-]{43,128}$/.test(verifier || '')) return json({ error: 'invalid_request' }, 400);
    const body = new URLSearchParams({ client_id: env.GOOGLE_CLIENT_ID, client_secret: env.GOOGLE_CLIENT_SECRET,
      redirect_uri: `https://${env.PUBLIC_HOST}/oauth/callback`, grant_type: 'authorization_code', code, code_verifier: verifier });
    const response = await fetchGoogle('https://oauth2.googleapis.com/token', { method: 'POST', body, redirect: 'error', signal: AbortSignal.timeout(15000), headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Cache-Control': 'no-store' } });
    if (!response.ok) { await response.body?.cancel(); return json({ error: 'authorization_failed' }, response.status === 429 ? 429 : 401); }
    const token = JSON.parse(await boundedText(response.body, 65536));
    if (typeof token.access_token !== 'string' || token.access_token.length > 8192 || !token.access_token ||
        token.token_type?.toLowerCase() !== 'bearer' || !token.scope?.split(' ').includes(scope)) return json({ error: 'read_permission_missing' }, 403);
    // Discard refresh_token, id_token and every other provider field, even if returned unexpectedly.
    return json({ access_token: token.access_token, token_type: 'Bearer', scope,
      expires_in: Math.max(1, Math.min(3600, Number(token.expires_in) || 3500)) });
  } catch { return json({ error: 'exchange_unavailable' }, 502); }
}
export default { fetch(request, env) { return handle(request, env); } };
