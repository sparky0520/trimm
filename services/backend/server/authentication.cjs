'use strict';

const crypto = require('node:crypto');

function safeEqual(left, right) {
  const a = Buffer.from(left); const b = Buffer.from(right);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function bearer(request) {
  const header = String(request.headers.authorization || '');
  const match = header.match(/^Bearer\s+([^\s]+)$/i);
  if (!match) throw Object.assign(new Error('Authentication required.'), { status: 401, code: 'missing_token' });
  return match[1];
}

function boundedStrings(value, maximum = 64) {
  const source = Array.isArray(value) ? value : typeof value === 'string' ? value.split(/\s+/) : [];
  return [...new Set(source.filter((item) => typeof item === 'string' && /^[a-zA-Z0-9:_-]{1,80}$/.test(item)))].slice(0, maximum);
}

function clerkIssuer(publishableKey) {
  const match = String(publishableKey || '').match(/^pk_(?:test|live)_([A-Za-z0-9_-]+)$/);
  if (!match) return '';
  try {
    const frontendApi = Buffer.from(match[1], 'base64url').toString('utf8').replace(/\$$/, '');
    return /^[a-z0-9.-]+$/i.test(frontendApi) ? `https://${frontendApi}` : '';
  } catch { return ''; }
}

class Authenticator {
  constructor(options = {}) {
    this.issuer = String(options.issuer || '');
    this.audience = String(options.audience || '');
    this.jwksUri = String(options.jwksUri || '');
    this.bootstrapToken = String(options.bootstrapToken || '');
    this.allowBootstrap = options.allowBootstrap === true;
    this.authorizedParties = new Set((Array.isArray(options.authorizedParties) ? options.authorizedParties : [])
      .filter((value) => typeof value === 'string' && value.length > 0 && value.length <= 300).slice(0, 16));
    this.isRevoked = typeof options.isRevoked === 'function' ? options.isRevoked : async () => false;
    this.remoteJwks = null;
  }

  configured() { return Boolean((this.issuer && this.jwksUri) || (this.allowBootstrap && this.bootstrapToken)); }

  assertProductionConfigured(environment = process.env) {
    if (environment.NODE_ENV === 'production' && (!this.issuer || !this.jwksUri)) {
      throw Object.assign(new Error('Production OIDC authentication is not configured.'), { code: 'auth_unconfigured' });
    }
  }

  async authenticate(request) {
    const token = bearer(request);
    if (this.allowBootstrap && this.bootstrapToken && safeEqual(token, this.bootstrapToken)) {
      return Object.freeze({ id: `development:${crypto.createHash('sha256').update(token).digest('hex').slice(0, 16)}`, scopes: Object.freeze(['development']), entitlements: Object.freeze([]), authentication: 'development-bootstrap' });
    }
    if (!this.issuer || !this.jwksUri) throw Object.assign(new Error('Production authentication is not configured.'), { status: 503, code: 'auth_unconfigured' });
    try {
      const { createRemoteJWKSet, jwtVerify } = await import('jose');
      this.remoteJwks ||= createRemoteJWKSet(new URL(this.jwksUri), { timeoutDuration: 5000, cooldownDuration: 30_000 });
      const verification = { issuer: this.issuer, algorithms: ['RS256', 'ES256'] };
      if (this.audience) verification.audience = this.audience;
      const { payload } = await jwtVerify(token, this.remoteJwks, verification);
      if (typeof payload.sub !== 'string' || !/^[^\s]{1,200}$/.test(payload.sub)) throw new Error('Token subject is invalid.');
      if (this.authorizedParties.size && (typeof payload.azp !== 'string' || !this.authorizedParties.has(payload.azp))) throw new Error('Token authorized party is invalid.');
      if (await this.isRevoked({ subject: payload.sub, tokenId: typeof payload.jti === 'string' ? payload.jti : null })) throw new Error('Token has been revoked.');
      return Object.freeze({
        id: payload.sub,
        scopes: Object.freeze(boundedStrings(payload.scope || payload.permissions)),
        // Paid access is resolved from ctrlB's billing store. Identity-provider
        // custom claims are intentionally not an authorization source.
        entitlements: Object.freeze([]),
        authentication: 'oidc',
      });
    } catch {
      throw Object.assign(new Error('Access token is invalid or expired.'), { status: 401, code: 'invalid_token' });
    }
  }
}

function fromEnvironment(environment = process.env) {
  const production = environment.NODE_ENV === 'production';
  const derivedIssuer = clerkIssuer(environment.CTRLB_CLERK_PUBLISHABLE_KEY || environment.VITE_CLERK_PUBLISHABLE_KEY);
  const issuer = derivedIssuer || environment.CTRLB_AUTH_ISSUER;
  const revokedTokenIds = new Set(String(environment.CTRLB_AUTH_REVOKED_TOKEN_IDS || '').split(',').map((value) => value.trim()).filter(Boolean));
  return new Authenticator({
    issuer,
    audience: environment.CTRLB_AUTH_AUDIENCE,
    jwksUri: derivedIssuer ? `${derivedIssuer}/.well-known/jwks.json` : environment.CTRLB_AUTH_JWKS_URL,
    authorizedParties: String(environment.CTRLB_AUTH_AUTHORIZED_PARTIES || '').split(',').map((value) => value.trim()).filter(Boolean),
    bootstrapToken: environment.CTRLB_PLANNER_CLIENT_TOKEN,
    allowBootstrap: !production && environment.CTRLB_ALLOW_BOOTSTRAP_AUTH === 'true',
    isRevoked: ({ tokenId }) => tokenId !== null && revokedTokenIds.has(tokenId),
  });
}

module.exports = { Authenticator, bearer, boundedStrings, clerkIssuer, fromEnvironment };
