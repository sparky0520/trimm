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

class Authenticator {
  constructor(options = {}) {
    this.jwksUrl = options.jwksUrl ? String(options.jwksUrl) : null;
    this.bootstrapToken = String(options.bootstrapToken || '');
    this.allowBootstrap = options.allowBootstrap === true;
    this.issuer = options.issuer ? String(options.issuer) : null;
    this.audience = options.audience ? String(options.audience) : null;
    this.authorizedParties = options.authorizedParties ? new Set(options.authorizedParties) : null;
    this.isRevoked = typeof options.isRevoked === 'function' ? options.isRevoked : async () => false;
    // Lazily initialized JWKS set — cached across requests
    this._jwks = null;
  }

  configured() { return Boolean(this.jwksUrl || (this.allowBootstrap && this.bootstrapToken)); }

  assertProductionConfigured(environment = process.env) {
    if (environment.NODE_ENV === 'production' && !this.jwksUrl) {
      throw Object.assign(new Error('Production authentication is not configured.'), { code: 'auth_unconfigured' });
    }
  }

  _getJwks() {
    if (!this._jwks) {
      // jose is ESM; require the CJS-compatible interop via dynamic import cached here
      const { createRemoteJWKSet } = require('jose');
      this._jwks = createRemoteJWKSet(new URL(this.jwksUrl));
    }
    return this._jwks;
  }

  async authenticate(request) {
    const token = bearer(request);

    // Check bootstrap token for development
    if (this.allowBootstrap && this.bootstrapToken && safeEqual(token, this.bootstrapToken)) {
      return Object.freeze({
        sub: `development:${crypto.createHash('sha256').update(token).digest('hex').slice(0, 16)}`,
        id: `development:${crypto.createHash('sha256').update(token).digest('hex').slice(0, 16)}`,
        authentication: 'development-bootstrap',
      });
    }

    if (!this.jwksUrl) throw Object.assign(new Error('Production authentication is not configured.'), { status: 503, code: 'auth_unconfigured' });

    try {
      const { jwtVerify } = require('jose');
      const verifyOptions = { clockTolerance: 5 };
      if (this.issuer) verifyOptions.issuer = this.issuer;
      if (this.audience) verifyOptions.audience = this.audience;
      const { payload } = await jwtVerify(token, this._getJwks(), verifyOptions);

      if (typeof payload.sub !== 'string' || !/^[^\s]{1,200}$/.test(payload.sub)) {
        throw new Error('Token subject is invalid.');
      }

      if (this.authorizedParties && payload.azp && !this.authorizedParties.has(String(payload.azp))) {
        throw new Error('Authorized party is not trusted.');
      }

      if (await this.isRevoked({ subject: payload.sub, tokenId: typeof payload.jti === 'string' ? payload.jti : null })) {
        throw new Error('Token has been revoked.');
      }

      return Object.freeze({
        sub: payload.sub,
        id: payload.sub,
        authentication: 'supabase-auth',
      });
    } catch (err) {
      throw Object.assign(new Error('Access token is invalid or expired.'), { status: 401, code: 'invalid_token' });
    }
  }
}

function fromEnvironment(environment = process.env) {
  const revokedTokenIds = new Set(String(environment.TRIMM_AUTH_REVOKED_TOKEN_IDS || '').split(',').map((value) => value.trim()).filter(Boolean));

  return new Authenticator({
    jwksUrl: environment.SUPABASE_JWKS_URL,
    bootstrapToken: '',
    allowBootstrap: false,
    isRevoked: ({ tokenId }) => tokenId !== null && revokedTokenIds.has(tokenId),
  });
}

module.exports = { Authenticator, bearer, fromEnvironment };
