'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { Authenticator, clerkIssuer, fromEnvironment } = require('../server/authentication.cjs');

const request = (token) => ({ headers: token ? { authorization: `Bearer ${token}` } : {} });

test('development bootstrap authentication is explicit and visibly non-user', async () => {
  const disabled = new Authenticator({ bootstrapToken: 'secret', allowBootstrap: false });
  await assert.rejects(disabled.authenticate(request('secret')), (error) => error.status === 503);
  const enabled = new Authenticator({ bootstrapToken: 'secret', allowBootstrap: true });
  const principal = await enabled.authenticate(request('secret'));
  assert.match(principal.id, /^development:/);
  assert.equal(principal.authentication, 'development-bootstrap');
  await assert.rejects(enabled.authenticate(request()), (error) => error.code === 'missing_token');
  const production = fromEnvironment({ NODE_ENV: 'production', CTRLB_ALLOW_BOOTSTRAP_AUTH: 'true', CTRLB_PLANNER_CLIENT_TOKEN: 'secret' });
  await assert.rejects(production.authenticate(request('secret')), (error) => error.code === 'auth_unconfigured');
  assert.throws(() => production.assertProductionConfigured({ NODE_ENV: 'production' }), (error) => error.code === 'auth_unconfigured');
  enabled.assertProductionConfigured({ NODE_ENV: 'development' });
});

test('Clerk publishable keys provide a provider-neutral issuer and JWKS boundary', () => {
  const key = `pk_test_${Buffer.from('oriented-rooster-53.clerk.accounts.dev$').toString('base64url')}`;
  assert.equal(clerkIssuer(key), 'https://oriented-rooster-53.clerk.accounts.dev');
  assert.equal(clerkIssuer('not-a-key'), '');
  const auth = fromEnvironment({ VITE_CLERK_PUBLISHABLE_KEY: key, CTRLB_AUTH_AUTHORIZED_PARTIES: 'http://localhost:5173' });
  assert.equal(auth.issuer, 'https://oriented-rooster-53.clerk.accounts.dev');
  assert.equal(auth.jwksUri, 'https://oriented-rooster-53.clerk.accounts.dev/.well-known/jwks.json');
  assert.deepEqual([...auth.authorizedParties], ['http://localhost:5173']);
});

test('OIDC authentication verifies signature, issuer, audience, expiry, and canonical subject', async (t) => {
  const { SignJWT, exportJWK, generateKeyPair } = await import('jose');
  const { publicKey, privateKey } = await generateKeyPair('RS256');
  const jwk = { ...(await exportJWK(publicKey)), kid: 'test-key', alg: 'RS256', use: 'sig' };
  const server = http.createServer((_request, response) => { response.setHeader('content-type', 'application/json'); response.end(JSON.stringify({ keys: [jwk] })); });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const issuer = 'https://identity.ctrlb.test/'; const audience = 'ctrlb-api';
  const auth = new Authenticator({ issuer, audience, jwksUri: `http://127.0.0.1:${server.address().port}/jwks`, authorizedParties: ['ctrlb://desktop'], isRevoked: ({ tokenId }) => tokenId === 'revoked-token' });
  auth.assertProductionConfigured({ NODE_ENV: 'production' });
  const sign = (tokenIssuer = issuer, tokenAudience = audience, tokenId = 'active-token', authorizedParty = 'ctrlb://desktop') => new SignJWT({ scope: 'transcribe projects:write', entitlements: ['pro'], azp: authorizedParty }).setProtectedHeader({ alg: 'RS256', kid: 'test-key' }).setSubject('user-123').setIssuer(tokenIssuer).setAudience(tokenAudience).setJti(tokenId).setIssuedAt().setExpirationTime('5m').sign(privateKey);
  const principal = await auth.authenticate(request(await sign()));
  assert.deepEqual(principal, { id: 'user-123', scopes: ['transcribe', 'projects:write'], entitlements: [], authentication: 'oidc' });
  await assert.rejects(auth.authenticate(request(await sign('https://attacker.test/'))), (error) => error.code === 'invalid_token');
  await assert.rejects(auth.authenticate(request(await sign(issuer, 'wrong-api'))), (error) => error.code === 'invalid_token');
  const expired = await new SignJWT({}).setProtectedHeader({ alg: 'RS256', kid: 'test-key' }).setSubject('user-123').setIssuer(issuer).setAudience(audience).setIssuedAt().setExpirationTime('0s').sign(privateKey);
  await assert.rejects(auth.authenticate(request(expired)), (error) => error.code === 'invalid_token');
  await assert.rejects(auth.authenticate(request(await sign(issuer, audience, 'revoked-token'))), (error) => error.code === 'invalid_token');
  await assert.rejects(auth.authenticate(request(await sign(issuer, audience, 'active-token', 'https://attacker.test'))), (error) => error.code === 'invalid_token');
  await assert.rejects(auth.authenticate(request('not-a-jwt')), (error) => error.code === 'invalid_token' && !error.message.includes('not-a-jwt'));
});
