'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { Authenticator } = require('../server/authentication.cjs');

const request = (token) => ({ headers: token ? { authorization: `Bearer ${token}` } : {} });

test('development bootstrap authentication is explicit and visibly non-user', async () => {
  const disabled = new Authenticator({ bootstrapToken: 'secret', allowBootstrap: false });
  await assert.rejects(disabled.authenticate(request('secret')), (error) => error.status === 503);
  const enabled = new Authenticator({ bootstrapToken: 'secret', allowBootstrap: true });
  const principal = await enabled.authenticate(request('secret'));
  assert.match(principal.id, /^development:/);
  assert.equal(principal.authentication, 'development-bootstrap');
  await assert.rejects(enabled.authenticate(request()), (error) => error.code === 'missing_token');
});

test('OIDC authentication verifies signature, issuer, audience, expiry, and canonical subject', async (t) => {
  const { SignJWT, exportJWK, generateKeyPair } = await import('jose');
  const { publicKey, privateKey } = await generateKeyPair('RS256');
  const jwk = { ...(await exportJWK(publicKey)), kid: 'test-key', alg: 'RS256', use: 'sig' };
  const server = http.createServer((_request, response) => { response.setHeader('content-type', 'application/json'); response.end(JSON.stringify({ keys: [jwk] })); });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const issuer = 'https://identity.ctrlb.test/'; const audience = 'ctrlb-api';
  const auth = new Authenticator({ issuer, audience, jwksUri: `http://127.0.0.1:${server.address().port}/jwks` });
  const sign = (tokenIssuer = issuer, tokenAudience = audience) => new SignJWT({ scope: 'transcribe projects:write', entitlements: ['pro'] }).setProtectedHeader({ alg: 'RS256', kid: 'test-key' }).setSubject('user-123').setIssuer(tokenIssuer).setAudience(tokenAudience).setIssuedAt().setExpirationTime('5m').sign(privateKey);
  const principal = await auth.authenticate(request(await sign()));
  assert.deepEqual(principal, { id: 'user-123', scopes: ['transcribe', 'projects:write'], entitlements: ['pro'], authentication: 'oidc' });
  await assert.rejects(auth.authenticate(request(await sign('https://attacker.test/'))), (error) => error.code === 'invalid_token');
  await assert.rejects(auth.authenticate(request(await sign(issuer, 'wrong-api'))), (error) => error.code === 'invalid_token');
  const expired = await new SignJWT({}).setProtectedHeader({ alg: 'RS256', kid: 'test-key' }).setSubject('user-123').setIssuer(issuer).setAudience(audience).setIssuedAt().setExpirationTime('0s').sign(privateKey);
  await assert.rejects(auth.authenticate(request(expired)), (error) => error.code === 'invalid_token');
});
