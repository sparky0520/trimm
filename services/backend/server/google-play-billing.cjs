'use strict';

const crypto = require('node:crypto');
const { GoogleAuth } = require('google-auth-library');
const { billingError } = require('./billing-core.cjs');

const PACKAGE_NAME = 'ai.ctrlb.mobile';
const PRODUCT_PLANS = Object.freeze({
  ctrlb_basic_monthly: 'basic',
  ctrlb_pro_monthly: 'pro',
});
const GOOGLE_STATES = Object.freeze({
  SUBSCRIPTION_STATE_PENDING: 'past_due',
  SUBSCRIPTION_STATE_ACTIVE: 'active',
  SUBSCRIPTION_STATE_PAUSED: 'past_due',
  SUBSCRIPTION_STATE_IN_GRACE_PERIOD: 'grace_period',
  SUBSCRIPTION_STATE_ON_HOLD: 'past_due',
  SUBSCRIPTION_STATE_CANCELED: 'cancelled',
  SUBSCRIPTION_STATE_EXPIRED: 'expired',
  SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED: 'cancelled',
});

function tokenHash(value) {
  return crypto.createHash('sha256').update(String(value)).digest('hex');
}

function obfuscatedAccountId(canonicalUserId) {
  return tokenHash(`ctrlb-google-play:${canonicalUserId}`);
}

function serviceAccount(environment = process.env) {
  const raw = environment.CTRLB_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON;
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    if (!parsed.client_email || !parsed.private_key) throw new Error('incomplete credentials');
    return parsed;
  } catch {
    throw billingError('Google Play service account JSON is invalid.', 503, 'billing_unconfigured');
  }
}

function normalizeGoogleSubscription({ purchase, purchaseToken, productId, canonicalUserId, now = new Date() }) {
  const planId = PRODUCT_PLANS[productId];
  const state = GOOGLE_STATES[purchase?.subscriptionState];
  const account = purchase?.externalAccountIdentifiers?.obfuscatedExternalAccountId;
  const lineItem = purchase?.lineItems?.find((item) => item?.productId === productId);
  if (!planId || !state || !lineItem || account !== obfuscatedAccountId(canonicalUserId)) {
    throw billingError('Google Play purchase does not match this account or product.', 403, 'purchase_verification_failed');
  }
  return {
    eventId: `purchase-${tokenHash(purchaseToken)}`,
    providerCustomerId: `purchase-${tokenHash(purchaseToken)}`,
    canonicalUserId,
    planId,
    state,
    occurredAt: purchase.startTime || now.toISOString(),
    periodEndsAt: lineItem.expiryTime || null,
    acknowledgementPending: purchase.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_PENDING',
  };
}

class GooglePlayPurchaseVerifier {
  constructor({
    credentials = serviceAccount(),
    packageName = PACKAGE_NAME,
    auth,
    request,
  } = {}) {
    if (!credentials && !auth && !request) {
      throw billingError('Google Play purchase verification is not configured.', 503, 'billing_unconfigured');
    }
    this.packageName = packageName;
    this.auth = auth || new GoogleAuth({
      credentials,
      scopes: ['https://www.googleapis.com/auth/androidpublisher'],
    });
    this.request = request;
  }

  async client() {
    return this.request ? { request: this.request } : this.auth.getClient();
  }

  async verify({ purchaseToken, productId, canonicalUserId }) {
    if (typeof purchaseToken !== 'string' || purchaseToken.length < 10 || purchaseToken.length > 4096
      || !PRODUCT_PLANS[productId]) {
      throw billingError('Invalid Google Play purchase submission.', 400, 'invalid_purchase');
    }
    try {
      const client = await this.client();
      const encodedToken = encodeURIComponent(purchaseToken);
      const response = await client.request({
        method: 'GET',
        url: `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${this.packageName}/purchases/subscriptionsv2/tokens/${encodedToken}`,
      });
      return normalizeGoogleSubscription({
        purchase: response.data,
        purchaseToken,
        productId,
        canonicalUserId,
      });
    } catch (error) {
      if (error?.code && error?.status) throw error;
      throw billingError('Google Play could not verify this purchase.', 502, 'purchase_verification_failed');
    }
  }

  async acknowledge({ purchaseToken, productId }) {
    const client = await this.client();
    await client.request({
      method: 'POST',
      url: `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${this.packageName}/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`,
      data: {},
    });
  }
}

module.exports = {
  GOOGLE_STATES,
  GooglePlayPurchaseVerifier,
  PACKAGE_NAME,
  PRODUCT_PLANS,
  normalizeGoogleSubscription,
  obfuscatedAccountId,
  serviceAccount,
  tokenHash,
};
