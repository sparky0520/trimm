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

class Authenticator {
  constructor(options = {}) {
    this.issuer = String(options.issuer || '');
    this.audience = String(options.audience || '');
    this.jwksUri = String(options.jwksUri || '');
    this.bootstrapToken = String(options.bootstrapToken || '');
    this.allowBootstrap = options.allowBootstrap === true;
    this.remoteJwks = null;
  }

  configured() { return Boolean((this.issuer && this.audience && this.jwksUri) || (this.allowBootstrap && this.bootstrapToken)); }

  async authenticate(request) {
    const token = bearer(request);
    if (this.allowBootstrap && this.bootstrapToken && safeEqual(token, this.bootstrapToken)) {
      return Object.freeze({ id: `development:${crypto.createHash('sha256').update(token).digest('hex').slice(0, 16)}`, scopes: Object.freeze(['development']), entitlements: Object.freeze([]), authentication: 'development-bootstrap' });
    }
    if (!this.issuer || !this.audience || !this.jwksUri) throw Object.assign(new Error('Production authentication is not configured.'), { status: 503, code: 'auth_unconfigured' });
    try {
      const { createRemoteJWKSet, jwtVerify } = await import('jose');
      this.remoteJwks ||= createRemoteJWKSet(new URL(this.jwksUri), { timeoutDuration: 5000, cooldownDuration: 30_000 });
      const { payload } = await jwtVerify(token, this.remoteJwks, { issuer: this.issuer, audience: this.audience, algorithms: ['RS256', 'ES256'] });
      if (typeof payload.sub !== 'string' || !/^[^\s]{1,200}$/.test(payload.sub)) throw new Error('Token subject is invalid.');
      return Object.freeze({
        id: payload.sub,
        scopes: Object.freeze(boundedStrings(payload.scope || payload.permissions)),
        entitlements: Object.freeze(boundedStrings(payload.entitlements)),
        authentication: 'oidc',
      });
    } catch {
      throw Object.assign(new Error('Access token is invalid or expired.'), { status: 401, code: 'invalid_token' });
    }
  }
}

function fromEnvironment(environment = process.env) {
  return new Authenticator({
    issuer: environment.CTRLB_AUTH_ISSUER,
    audience: environment.CTRLB_AUTH_AUDIENCE,
    jwksUri: environment.CTRLB_AUTH_JWKS_URL,
    bootstrapToken: environment.CTRLB_PLANNER_CLIENT_TOKEN,
    allowBootstrap: environment.CTRLB_ALLOW_BOOTSTRAP_AUTH === 'true',
  });
}

module.exports = { Authenticator, bearer, boundedStrings, fromEnvironment };
