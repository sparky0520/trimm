
import test from 'node:test';
import assert from 'node:assert/strict';
import { IdentityRateLimiter, auditEvent, correlationId, requireEntitlement, requireOwner, requireScope, } from '../middleware/authorization.js';

const principal = Object.freeze({ id: 'user-a', scopes: ['plan:read'], entitlements: ['pro'], authentication: 'oidc' });

test('authorization separates authentication, scope, entitlement, and opaque ownership checks', () => {
  assert.equal(requireScope(principal, 'plan:read'), principal);
  assert.throws(() => requireScope(principal, 'transcribe:create'), (error) => error.status === 403 && error.code === 'insufficient_scope');
  assert.equal(requireEntitlement(principal, 'pro'), principal);
  assert.throws(() => requireEntitlement(principal, 'studio'), (error) => error.code === 'entitlement_required');
  assert.equal(requireOwner(principal, 'user-a'), principal);
  assert.throws(() => requireOwner(principal, 'user-b'), (error) => error.status === 404 && error.message === 'Resource not found.');
  assert.doesNotThrow(() => requireScope({ id: 'development:x', scopes: ['development'], authentication: 'development-bootstrap' }, 'any:scope'));
});

test('rate limiting keys authenticated users canonically and bounds network fallback', () => {
  let now = 100;
  const limiter = new IdentityRateLimiter({ limit: 2, windowMs: 50, now: () => now });
  const request = { headers: { 'x-forwarded-for': '203.0.113.4' }, socket: {} };
  limiter.consume(request, principal);
  limiter.consume(request, principal);
  assert.throws(() => limiter.consume({ headers: {}, socket: { remoteAddress: '127.0.0.1' } }, principal), (error) => error.status === 429);
  assert.doesNotThrow(() => limiter.consume(request, { ...principal, id: 'user-b' }));
  now = 151;
  assert.doesNotThrow(() => limiter.consume(request, principal));
  assert.match(limiter.key(request, null), /^network:[0-9a-f]{16}$/);
});

test('audit events contain bounded identifiers and no request secrets', () => {
  const requestId = correlationId({ headers: { 'x-request-id': 'request-123' } });
  assert.equal(requestId, 'request-123');
  const event = auditEvent({ principal, action: 'create', resourceType: 'transcription', outcome: 'allowed', requestId });
  assert.deepEqual(Object.keys(event), ['timestamp', 'requestId', 'userId', 'action', 'resourceType', 'outcome']);
  assert.equal(event.userId, 'user-a');
  assert.equal(JSON.stringify(event).includes('Bearer'), false);
  assert.match(correlationId({ headers: { 'x-request-id': 'bad request id' } }), /^[0-9a-f-]{36}$/);
});
