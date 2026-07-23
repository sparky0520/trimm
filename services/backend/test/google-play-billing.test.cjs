'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const {
  GooglePlayPurchaseVerifier,
  normalizeGoogleSubscription,
  obfuscatedAccountId,
  tokenHash,
} = require('../server/google-play-billing.cjs');

const canonicalUserId = 'user_01-test';
const purchaseToken = 'purchase-token-1234567890';
const productId = 'ctrlb_pro_monthly';
const purchase = {
  subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
  acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING',
  startTime: '2026-07-23T10:00:00Z',
  externalAccountIdentifiers: {
    obfuscatedExternalAccountId: obfuscatedAccountId(canonicalUserId),
  },
  lineItems: [{
    productId,
    expiryTime: '2026-08-23T10:00:00Z',
  }],
};

test('Google subscription verification binds the token to the signed-in account', () => {
  assert.deepEqual(normalizeGoogleSubscription({
    purchase, purchaseToken, productId, canonicalUserId,
  }), {
    eventId: `purchase-${tokenHash(purchaseToken)}`,
    providerCustomerId: `purchase-${tokenHash(purchaseToken)}`,
    canonicalUserId,
    planId: 'pro',
    state: 'active',
    occurredAt: purchase.startTime,
    periodEndsAt: purchase.lineItems[0].expiryTime,
    acknowledgementPending: true,
  });
  assert.throws(
    () => normalizeGoogleSubscription({
      purchase, purchaseToken, productId, canonicalUserId: 'different-user',
    }),
    (error) => error.code === 'purchase_verification_failed',
  );
});

test('Google verifier calls subscriptionsv2 and acknowledges verified purchases', async () => {
  const requests = [];
  const verifier = new GooglePlayPurchaseVerifier({
    request: async (request) => {
      requests.push(request);
      return { data: purchase };
    },
  });
  const verified = await verifier.verify({ purchaseToken, productId, canonicalUserId });
  assert.equal(verified.planId, 'pro');
  assert.match(requests[0].url, /purchases\/subscriptionsv2\/tokens\//);
  await verifier.acknowledge({ purchaseToken, productId });
  assert.match(requests[1].url, /:acknowledge$/);
  assert.equal(requests[1].method, 'POST');
});
