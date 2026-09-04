'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { Authenticator, fromEnvironment } = require('../server/authentication.cjs');

const request = (token) => ({ headers: token ? { authorization: `Bearer ${token}` } : {} });

test('development bootstrap authentication is explicit and visibly non-user', async () => {
  const disabled = new Authenticator({ bootstrapToken: 'secret', allowBootstrap: false });
  await assert.rejects(disabled.authenticate(request('secret')), (error) => error.status === 503);
  const enabled = new Authenticator({ bootstrapToken: 'secret', allowBootstrap: true });
  const principal = await enabled.authenticate(request('secret'));
  assert.match(principal.id, /^development:/);
  assert.equal(principal.authentication, 'development-bootstrap');
  await assert.rejects(enabled.authenticate(request()), (error) => error.code === 'missing_token');
  const production = fromEnvironment({ NODE_ENV: 'production', TRIMM_ALLOW_BOOTSTRAP_AUTH: 'true', TRIMM_PLANNER_CLIENT_TOKEN: 'secret' });
  await assert.rejects(production.authenticate(request('secret')), (error) => error.code === 'auth_unconfigured');
  assert.throws(() => production.assertProductionConfigured({ NODE_ENV: 'production' }), (error) => error.code === 'auth_unconfigured');
  enabled.assertProductionConfigured({ NODE_ENV: 'development' });
});

test('Supabase JWKS URL provides a provider-neutral issuer and JWKS boundary', () => {
  const jwksUrl = 'https://abcdefghijklmnop.supabase.co/auth/v1/keys';
  const auth = fromEnvironment({ SUPABASE_JWKS_URL: jwksUrl });
  assert.equal(auth.jwksUrl, jwksUrl);
  assert.ok(!auth.allowBootstrap);
});

test('OIDC authentication verifies signature, issuer, audience, expiry, and canonical subject', async (t) => {
  const { SignJWT, exportJWK, generateKeyPair } = await import('jose');
  const { publicKey, privateKey } = await generateKeyPair('RS256');
  const jwk = { ...(await exportJWK(publicKey)), kid: 'test-key', alg: 'RS256', use: 'sig' };
  const server = http.createServer((_request, response) => { response.setHeader('content-type', 'application/json'); response.end(JSON.stringify({ keys: [jwk] })); });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const issuer = 'https://identity.trimm.test/'; const audience = 'trimm-api';
  const auth = new Authenticator({ issuer, audience, jwksUrl: `http://127.0.0.1:${server.address().port}/jwks`, authorizedParties: ['trimm://desktop'], isRevoked: ({ tokenId }) => tokenId === 'revoked-token' });
  auth.assertProductionConfigured({ NODE_ENV: 'production' });
  const sign = (tokenIssuer = issuer, tokenAudience = audience, tokenId = 'active-token', authorizedParty = 'trimm://desktop') => new SignJWT({ scope: 'transcribe projects:write', entitlements: ['pro'], azp: authorizedParty }).setProtectedHeader({ alg: 'RS256', kid: 'test-key' }).setSubject('user-123').setIssuer(tokenIssuer).setAudience(tokenAudience).setJti(tokenId).setIssuedAt().setExpirationTime('5m').sign(privateKey);
  const principal = await auth.authenticate(request(await sign()));
  assert.deepEqual(principal, {
    id: 'user-123',
    sub: 'user-123',
    scopes: ['*'],
    entitlements: ['pro'],
    authentication: 'supabase-auth',
  });
  await assert.rejects(auth.authenticate(request(await sign('https://attacker.test/'))), (error) => error.code === 'invalid_token');
  await assert.rejects(auth.authenticate(request(await sign(issuer, 'wrong-api'))), (error) => error.code === 'invalid_token');
  const expired = await new SignJWT({}).setProtectedHeader({ alg: 'RS256', kid: 'test-key' }).setSubject('user-123').setIssuer(issuer).setAudience(audience).setIssuedAt().setExpirationTime('-1m').sign(privateKey);
  await assert.rejects(auth.authenticate(request(expired)), (error) => error.code === 'invalid_token');
  await assert.rejects(auth.authenticate(request(await sign(issuer, audience, 'revoked-token'))), (error) => error.code === 'invalid_token');
  await assert.rejects(auth.authenticate(request(await sign(issuer, audience, 'active-token', 'https://attacker.test'))), (error) => error.code === 'invalid_token');
  await assert.rejects(auth.authenticate(request('not-a-jwt')), (error) => error.code === 'invalid_token' && !error.message.includes('not-a-jwt'));
});

test('Supabase principal exposes only safe non-PII claims from the verified JWT', async (t) => {
  const { SignJWT, exportJWK, generateKeyPair } = await import('jose');
  const { publicKey, privateKey } = await generateKeyPair('RS256');
  const jwk = { ...(await exportJWK(publicKey)), kid: 'scope-key', alg: 'RS256', use: 'sig' };
  const server = http.createServer((_request, response) => response.end(JSON.stringify({ keys: [jwk] })));
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const issuer = 'https://identity.trimm.test/';
  const auth = new Authenticator({
    issuer, jwksUrl: `http://127.0.0.1:${server.address().port}/jwks`,
  });
  const sign = (claims = {}) => new SignJWT(claims).setProtectedHeader({ alg: 'RS256', kid: 'scope-key' })
    .setSubject('mobile-user').setIssuer(issuer).setIssuedAt().setExpirationTime('5m').sign(privateKey);
  const principal = await auth.authenticate(request(await sign({ email: 'user@example.com', role: 'authenticated' })));
  assert.equal(principal.id, 'mobile-user');
  assert.equal(principal.sub, 'mobile-user');
  assert.equal(principal.authentication, 'supabase-auth');
  // Supabase principal must not expose raw JWT claims directly
  assert.equal(principal.email, undefined);
  assert.equal(principal.role, undefined);
});
