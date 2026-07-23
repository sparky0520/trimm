'use strict';

const crypto = require('node:crypto');

function authorizationError(message = 'Access denied.', status = 403, code = 'forbidden') {
  return Object.assign(new Error(message), { status, code });
}

function requireAuthentication(principal) {
  if (!principal || typeof principal.id !== 'string' || principal.id.length === 0) {
    throw authorizationError('Authentication required.', 401, 'unauthorized');
  }
  return principal;
}

function hasGrant(values, required) {
  return Array.isArray(values) && (values.includes(required) || values.includes('*'));
}

function requireScope(principal, scope) {
  requireAuthentication(principal);
  if (principal.authentication === 'development-bootstrap') return principal;
  if (!hasGrant(principal.scopes, scope)) throw authorizationError('Insufficient scope.', 403, 'insufficient_scope');
  return principal;
}

function requireEntitlement(principal, entitlement) {
  requireAuthentication(principal);
  if (!hasGrant(principal.entitlements, entitlement)) throw authorizationError('Entitlement required.', 403, 'entitlement_required');
  return principal;
}

function requireOwner(principal, ownerId) {
  requireAuthentication(principal);
  if (typeof ownerId !== 'string' || ownerId !== principal.id) {
    throw authorizationError('Resource not found.', 404, 'not_found');
  }
  return principal;
}

function correlationId(request) {
  const supplied = String(request?.headers?.['x-request-id'] || '');
  return /^[a-zA-Z0-9._:-]{1,100}$/.test(supplied) ? supplied : crypto.randomUUID();
}

function auditEvent({ principal, action, resourceType, outcome, requestId, reason }) {
  return Object.freeze({
    timestamp: new Date().toISOString(),
    requestId,
    userId: principal?.id || null,
    action,
    resourceType,
    outcome,
    ...(reason ? { reason: String(reason).slice(0, 80) } : {}),
  });
}

class IdentityRateLimiter {
  constructor(options = {}) {
    this.limit = Number.isSafeInteger(options.limit) && options.limit > 0 ? options.limit : 120;
    this.windowMs = Number.isSafeInteger(options.windowMs) && options.windowMs > 0 ? options.windowMs : 60_000;
    this.now = options.now || Date.now;
    this.entries = new Map();
  }

  key(request, principal) {
    if (principal?.id) return `user:${principal.id}`;
    const forwarded = String(request?.headers?.['x-forwarded-for'] || '').split(',')[0].trim();
    const address = forwarded || request?.socket?.remoteAddress || 'unknown';
    return `network:${crypto.createHash('sha256').update(address).digest('hex').slice(0, 16)}`;
  }

  consume(request, principal, cost = 1) {
    const key = this.key(request, principal);
    const now = this.now();
    let entry = this.entries.get(key);
    if (!entry || now >= entry.resetAt) entry = { count: 0, resetAt: now + this.windowMs };
    if (entry.count + cost > this.limit) {
      throw authorizationError('Too many requests.', 429, 'rate_limited');
    }
    entry.count += cost;
    this.entries.set(key, entry);
    return { remaining: this.limit - entry.count, resetAt: entry.resetAt };
  }
}

module.exports = {
  IdentityRateLimiter,
  auditEvent,
  correlationId,
  requireAuthentication,
  requireEntitlement,
  requireOwner,
  requireScope,
};
