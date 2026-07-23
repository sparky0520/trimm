'use strict';

const crypto = require('node:crypto');

const BILLING_CATALOG = Object.freeze({
  version: 1,
  currency: 'USD',
  plans: Object.freeze({
    free: Object.freeze({
      entitlements: Object.freeze(['local_editing']),
      quotas: Object.freeze({ plansPerMonth: 10, transcriptionMinutesPerMonth: 30, concurrentJobs: 1, maximumSourceMinutes: 15 }),
    }),
    basic: Object.freeze({
      entitlements: Object.freeze(['local_editing', 'cloud_planning', 'cloud_transcription']),
      quotas: Object.freeze({ plansPerMonth: 150, transcriptionMinutesPerMonth: 600, concurrentJobs: 2, maximumSourceMinutes: 60 }),
    }),
    pro: Object.freeze({
      entitlements: Object.freeze(['local_editing', 'cloud_planning', 'cloud_transcription', 'priority_processing']),
      quotas: Object.freeze({ plansPerMonth: 1000, transcriptionMinutesPerMonth: 3600, concurrentJobs: 5, maximumSourceMinutes: 180 }),
    }),
  }),
});

const ACTIVE_STATES = new Set(['trialing', 'active', 'grace_period']);
const KNOWN_STATES = new Set([...ACTIVE_STATES, 'past_due', 'cancelled', 'expired', 'refunded', 'revoked']);

function billingError(message, status, code) {
  return Object.assign(new Error(message), { status, code });
}

function boundedId(value, name) {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9:_-]{1,200}$/.test(value)) {
    throw billingError(`Invalid ${name}.`, 400, 'invalid_billing_event');
  }
  return value;
}

function normalizedEvent(value) {
  if (!value || typeof value !== 'object') throw billingError('Invalid billing event.', 400, 'invalid_billing_event');
  const occurredAt = new Date(value.occurredAt);
  if (!Number.isFinite(occurredAt.getTime()) || !KNOWN_STATES.has(value.state)) {
    throw billingError('Invalid billing event.', 400, 'invalid_billing_event');
  }
  const planId = boundedId(value.planId, 'plan');
  if (!BILLING_CATALOG.plans[planId]) throw billingError('Unknown billing plan.', 400, 'unknown_plan');
  const periodEndsAt = value.periodEndsAt ? new Date(value.periodEndsAt) : null;
  if (periodEndsAt && !Number.isFinite(periodEndsAt.getTime())) {
    throw billingError('Invalid billing event.', 400, 'invalid_billing_event');
  }
  return Object.freeze({
    eventId: boundedId(value.eventId, 'event ID'),
    provider: boundedId(value.provider, 'provider'),
    providerCustomerId: boundedId(value.providerCustomerId, 'provider customer ID'),
    canonicalUserId: boundedId(value.canonicalUserId, 'canonical user ID'),
    planId,
    state: value.state,
    occurredAt: occurredAt.toISOString(),
    periodEndsAt: periodEndsAt?.toISOString() || null,
  });
}

class VolatileBillingStore {
  constructor() {
    this.events = new Set();
    this.accounts = new Map();
    this.providerCustomers = new Map();
  }
  hasEvent(id) { return this.events.has(id); }
  account(userId) { return this.accounts.get(userId) || null; }
  userForCustomer(provider, customerId) { return this.providerCustomers.get(`${provider}:${customerId}`) || null; }
  async apply(event) {
    const eventKey = `${event.provider}:${event.eventId}`;
    if (this.events.has(eventKey)) return { duplicate: true, account: this.account(event.canonicalUserId) };
    const mappedUser = this.userForCustomer(event.provider, event.providerCustomerId);
    if (mappedUser && mappedUser !== event.canonicalUserId) throw billingError('Billing account ownership conflict.', 409, 'billing_owner_conflict');
    this.providerCustomers.set(`${event.provider}:${event.providerCustomerId}`, event.canonicalUserId);
    this.events.add(eventKey);
    const current = this.account(event.canonicalUserId);
    if (!current || Date.parse(event.occurredAt) >= Date.parse(current.occurredAt)) {
      this.accounts.set(event.canonicalUserId, Object.freeze({ ...event }));
    }
    return { duplicate: false, account: this.account(event.canonicalUserId) };
  }
}

class BillingService {
  constructor({ store = new VolatileBillingStore(), verifiers = {}, now = () => new Date() } = {}) {
    this.store = store;
    this.verifiers = verifiers;
    this.now = now;
  }

  catalog() { return BILLING_CATALOG; }

  async accessFor(userId) {
    const billing = await this.store.account(userId);
    const enabled = Boolean(billing && ACTIVE_STATES.has(billing.state)
      && (!billing.periodEndsAt || Date.parse(billing.periodEndsAt) > this.now().getTime()));
    const planId = enabled ? billing.planId : 'free';
    const plan = BILLING_CATALOG.plans[planId];
    return Object.freeze({
      catalogVersion: BILLING_CATALOG.version,
      planId,
      state: billing?.state || 'free',
      periodEndsAt: billing?.periodEndsAt || null,
      entitlements: plan.entitlements,
      quotas: plan.quotas,
    });
  }

  async requireEntitlement(userId, entitlement) {
    const access = await this.accessFor(userId);
    if (!access.entitlements.includes(entitlement)) {
      throw billingError('Entitlement required.', 403, 'entitlement_required');
    }
    return access;
  }

  async ingest(provider, request) {
    const verifier = this.verifiers[provider];
    if (typeof verifier !== 'function') throw billingError('Billing provider is not configured.', 503, 'billing_unconfigured');
    const verified = await verifier(request);
    const event = normalizedEvent({ ...verified, provider });
    return this.store.apply(event);
  }

  async reconcile(provider, records) {
    if (!Array.isArray(records) || records.length > 10_000) throw billingError('Invalid reconciliation batch.', 400, 'invalid_reconciliation');
    return Promise.all(records.map((record) => this.store.apply(normalizedEvent({ ...record, provider }))));
  }
}

function hmacWebhookVerifier({ secret, parse = JSON.parse } = {}) {
  if (!secret) return null;
  return async ({ body, signature }) => {
    const raw = Buffer.isBuffer(body) ? body : Buffer.from(String(body || ''));
    const supplied = Buffer.from(String(signature || ''), 'hex');
    const expected = crypto.createHmac('sha256', secret).update(raw).digest();
    if (supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected)) {
      throw billingError('Invalid webhook signature.', 401, 'invalid_webhook_signature');
    }
    return parse(raw.toString('utf8'));
  };
}

module.exports = {
  ACTIVE_STATES, BILLING_CATALOG, BillingService, VolatileBillingStore, billingError,
  hmacWebhookVerifier, normalizedEvent,
};
