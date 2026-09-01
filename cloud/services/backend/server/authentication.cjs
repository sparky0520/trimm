'use strict';

const crypto = require('node:crypto');
const jwt = require('jsonwebtoken');

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
    this.jwtSecret = String(options.jwtSecret || '');
    this.bootstrapToken = String(options.bootstrapToken || '');
    this.allowBootstrap = options.allowBootstrap === true;
    this.isRevoked = typeof options.isRevoked === 'function' ? options.isRevoked : async () => false;
  }

  configured() { return Boolean(this.jwtSecret || (this.allowBootstrap && this.bootstrapToken)); }

  assertProductionConfigured(environment = process.env) {
    if (environment.NODE_ENV === 'production' && !this.jwtSecret) {
      throw Object.assign(new Error('Production authentication is not configured.'), { code: 'auth_unconfigured' });
    }
  }

  async authenticate(request) {
    const token = bearer(request);
    
    // Check bootstrap token for development
    if (this.allowBootstrap && this.bootstrapToken && safeEqual(token, this.bootstrapToken)) {
      return Object.freeze({ 
        sub: `development:${crypto.createHash('sha256').update(token).digest('hex').slice(0, 16)}`, 
        authentication: 'development-bootstrap' 
      });
    }
    
    if (!this.jwtSecret) throw Object.assign(new Error('Production authentication is not configured.'), { status: 503, code: 'auth_unconfigured' });
    
    try {
      const payload = jwt.verify(token, this.jwtSecret);
      
      if (typeof payload.sub !== 'string' || !/^[^\s]{1,200}$/.test(payload.sub)) {
        throw new Error('Token subject is invalid.');
      }
      
      if (await this.isRevoked({ subject: payload.sub, tokenId: typeof payload.jti === 'string' ? payload.jti : null })) {
        throw new Error('Token has been revoked.');
      }
      
      return Object.freeze({
        sub: payload.sub,
        authentication: 'supabase-auth',
      });
    } catch (err) {
      throw Object.assign(new Error('Access token is invalid or expired.'), { status: 401, code: 'invalid_token' });
    }
  }
}

function fromEnvironment(environment = process.env) {
  const production = environment.NODE_ENV === 'production';
  const revokedTokenIds = new Set(String(environment.TRIMM_AUTH_REVOKED_TOKEN_IDS || '').split(',').map((value) => value.trim()).filter(Boolean));
  
  return new Authenticator({
    jwtSecret: environment.SUPABASE_JWT_SECRET,
    bootstrapToken: '',
    allowBootstrap: false,
    isRevoked: ({ tokenId }) => tokenId !== null && revokedTokenIds.has(tokenId),
  });
}

module.exports = { Authenticator, bearer, fromEnvironment };
