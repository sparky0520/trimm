'use strict';

const { Environment, Paddle } = require('@paddle/paddle-node-sdk');
const { billingError } = require('./billing-core.cjs');

const PADDLE_STATUS = Object.freeze({
  active: 'active',
  trialing: 'trialing',
  past_due: 'past_due',
  paused: 'past_due',
  canceled: 'cancelled',
});

function paddlePricePlans(environment = process.env) {
  return new Map([
    [environment.CTRLB_PADDLE_BASIC_PRICE_ID, 'basic'],
    [environment.CTRLB_PADDLE_PRO_PRICE_ID, 'pro'],
  ].filter(([priceId]) => typeof priceId === 'string' && priceId.length > 0));
}

function paddleCanonicalUser(data) {
  const customData = data?.customData || data?.custom_data;
  return customData?.canonical_user_id || customData?.canonicalUserId || '';
}

function normalizePaddleEvent(event, pricePlans) {
  const data = event?.data;
  const eventType = event?.eventType || event?.event_type;
  if (!data || typeof eventType !== 'string' || !eventType.startsWith('subscription.')) {
    throw billingError('Unsupported Paddle webhook event.', 400, 'unsupported_billing_event');
  }
  const priceId = data.items?.find((item) => item?.status !== 'archived')?.price?.id;
  const planId = pricePlans.get(priceId);
  const state = PADDLE_STATUS[data.status];
  const canonicalUserId = paddleCanonicalUser(data);
  const eventId = event.eventId || event.event_id;
  const occurredAt = event.occurredAt || event.occurred_at;
  const providerCustomerId = data.customerId || data.customer_id;
  const period = data.currentBillingPeriod || data.current_billing_period;
  if (!planId || !state || !canonicalUserId) {
    throw billingError('Paddle subscription is missing trusted account or plan metadata.', 400, 'invalid_billing_event');
  }
  return {
    eventId,
    providerCustomerId,
    canonicalUserId,
    planId,
    state,
    occurredAt,
    periodEndsAt: period?.endsAt || period?.ends_at || null,
  };
}

function createPaddleWebhookVerifier({
  secret,
  apiKey = 'sandbox-placeholder',
  environment = Environment.sandbox,
  pricePlans = paddlePricePlans(),
  paddle = new Paddle(apiKey, { environment }),
} = {}) {
  if (!secret) return null;
  return async ({ body, signature }) => {
    if (!signature) throw billingError('Missing Paddle signature.', 401, 'invalid_webhook_signature');
    try {
      const rawBody = Buffer.isBuffer(body) ? body.toString('utf8') : String(body || '');
      const event = await paddle.webhooks.unmarshal(rawBody, secret, String(signature));
      return normalizePaddleEvent(event, pricePlans);
    } catch (error) {
      if (error?.code && error?.status) throw error;
      throw billingError('Invalid Paddle webhook signature or payload.', 401, 'invalid_webhook_signature');
    }
  };
}

module.exports = {
  PADDLE_STATUS,
  createPaddleWebhookVerifier,
  normalizePaddleEvent,
  paddleCanonicalUser,
  paddlePricePlans,
};
