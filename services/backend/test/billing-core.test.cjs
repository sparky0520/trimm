'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const crypto = require('node:crypto');
const { BillingService, VolatileBillingStore, hmacWebhookVerifier } = require('../server/billing-core.cjs');

const active = {
  eventId: 'evt-1', providerCustomerId: 'customer-1', canonicalUserId: 'user-1',
  planId: 'basic', state: 'active', occurredAt: '2026-07-01T00:00:00.000Z',
  periodEndsAt: '2026-08-01T00:00:00.000Z',
};

test('server-owned billing state grants access and ignores stale order', async () => {
  const service = new BillingService({ now: () => new Date('2026-07-23T00:00:00Z') });
  await service.reconcile('web', [active]);
  assert.deepEqual((await service.accessFor('user-1')).entitlements, ['local_editing', 'cloud_planning', 'cloud_transcription']);
  await service.reconcile('web', [{ ...active, eventId: 'evt-old', state: 'revoked', occurredAt: '2026-06-01T00:00:00Z' }]);
  assert.equal((await service.accessFor('user-1')).state, 'active');
});

test('duplicate events are idempotent and customer mappings cannot cross users', async () => {
  const store = new VolatileBillingStore();
  const service = new BillingService({ store });
  const [first] = await service.reconcile('android', [active]);
  const [second] = await service.reconcile('android', [active]);
  assert.equal(first.duplicate, false);
  assert.equal(second.duplicate, true);
  await assert.rejects(
    service.reconcile('android', [{ ...active, eventId: 'evt-2', canonicalUserId: 'user-2' }]),
    (error) => error.code === 'billing_owner_conflict',
  );
});

test('revoked, refunded, expired, and past-due states fall back to free access', async () => {
  for (const state of ['revoked', 'refunded', 'expired', 'past_due']) {
    const service = new BillingService();
    await service.reconcile('web', [{ ...active, eventId: `evt-${state}`, state }]);
    assert.equal((await service.accessFor('user-1')).planId, 'free');
    await assert.rejects(service.requireEntitlement('user-1', 'cloud_planning'), (error) => error.code === 'entitlement_required');
  }
});

test('signed webhook verifier rejects forgery and accepts the exact raw payload', async () => {
  const secret = 'test-secret';
  const body = Buffer.from(JSON.stringify(active));
  const verifier = hmacWebhookVerifier({ secret });
  await assert.rejects(verifier({ body, signature: '00' }), (error) => error.code === 'invalid_webhook_signature');
  const signature = crypto.createHmac('sha256', secret).update(body).digest('hex');
  assert.equal((await verifier({ body, signature })).eventId, active.eventId);
});
