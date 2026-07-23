'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { authorizeRequest, authorizationResponse } = require('../server/request-policy.cjs');

test('request policy attaches only the authenticated canonical principal', async () => {
  const canonical = Object.freeze({ id: 'canonical-user', scopes: ['plan:create'], entitlements: [], authentication: 'oidc' });
  const request = { headers: { 'x-request-id': 'policy-test' }, socket: { remoteAddress: '127.0.0.1' }, body: { userId: 'forged-user' } };
  const authentication = { authenticate: async () => canonical };
  const result = await authorizeRequest(request, authentication, { scope: 'plan:create', action: 'create', resourceType: 'plan' });
  assert.equal(result.principal, canonical);
  assert.equal(result.principal.id, 'canonical-user');
  assert.equal(result.requestId, 'policy-test');
});

test('request policy returns safe authorization errors with correlation IDs', async () => {
  const authentication = { authenticate: async () => ({ id: 'user', scopes: [], entitlements: [], authentication: 'oidc' }) };
  const request = { headers: {}, socket: { remoteAddress: '127.0.0.1' } };
  let failure;
  await assert.rejects(authorizeRequest(request, authentication, { scope: 'plan:create', action: 'create', resourceType: 'plan' }), (error) => {
    failure = error;
    return error.code === 'insufficient_scope' && /^[0-9a-f-]{36}$/.test(error.requestId);
  });
  const sent = {};
  const response = { status(value) { sent.status = value; return this; }, json(value) { sent.body = value; return value; } };
  authorizationResponse(response, failure);
  assert.equal(sent.status, 403);
  assert.deepEqual(Object.keys(sent.body), ['error', 'code', 'requestId']);
  assert.equal(JSON.stringify(sent.body).includes('token'), false);
});
