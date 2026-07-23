'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const {
  createPaddleWebhookVerifier,
  normalizePaddleEvent,
} = require('../server/paddle-billing.cjs');

const rawEvent = {
  event_id: 'evt_01ky7000000000000000000000',
  event_type: 'subscription.updated',
  occurred_at: '2026-07-23T10:00:00Z',
  data: {
    customer_id: 'ctm_01ky7000000000000000000000',
    status: 'active',
    custom_data: { canonical_user_id: 'user_01-test' },
    current_billing_period: { ends_at: '2026-08-23T10:00:00Z' },
    items: [{ status: 'active', price: { id: 'pri_basic' } }],
  },
};

test('Paddle subscription events map trusted custom data and price IDs', () => {
  assert.deepEqual(normalizePaddleEvent(rawEvent, new Map([['pri_basic', 'basic']])), {
    eventId: rawEvent.event_id,
    providerCustomerId: rawEvent.data.customer_id,
    canonicalUserId: 'user_01-test',
    planId: 'basic',
    state: 'active',
    occurredAt: rawEvent.occurred_at,
    periodEndsAt: rawEvent.data.current_billing_period.ends_at,
  });
});

test('Paddle events fail closed without account metadata or a known price', () => {
  assert.throws(
    () => normalizePaddleEvent({ ...rawEvent, data: { ...rawEvent.data, custom_data: null } }, new Map([['pri_basic', 'basic']])),
    (error) => error.code === 'invalid_billing_event',
  );
  assert.throws(
    () => normalizePaddleEvent(rawEvent, new Map()),
    (error) => error.code === 'invalid_billing_event',
  );
});

test('Paddle verifier delegates raw signature validation to the official SDK', async () => {
  let received;
  const paddle = {
    webhooks: {
      unmarshal: async (...args) => {
        received = args;
        return rawEvent;
      },
    },
  };
  const verifier = createPaddleWebhookVerifier({
    secret: 'pdl-secret',
    paddle,
    pricePlans: new Map([['pri_basic', 'basic']]),
  });
  const body = Buffer.from('{"raw":true}');
  const event = await verifier({ body, signature: 'ts=1;h1=test' });
  assert.deepEqual(received, ['{"raw":true}', 'pdl-secret', 'ts=1;h1=test']);
  assert.equal(event.planId, 'basic');
});
