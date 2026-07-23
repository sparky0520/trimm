'use strict';

const { IdentityRateLimiter, auditEvent, correlationId, requireScope } = require('./authorization.cjs');

const limiter = new IdentityRateLimiter({ limit: Number(process.env.CTRLB_RATE_LIMIT_PER_MINUTE) || 120 });

async function authorizeRequest(request, authentication, policy) {
  const requestId = correlationId(request);
  let principal;
  try {
    principal = await authentication.authenticate(request);
    requireScope(principal, policy.scope);
    limiter.consume(request, principal);
    console.info('[audit]', auditEvent({ principal, ...policy, outcome: 'allowed', requestId }));
    return { principal, requestId };
  } catch (error) {
    if (!principal) {
      try { limiter.consume(request, null); }
      catch (rateError) { rateError.requestId = requestId; error = rateError; }
    }
    console.info('[audit]', auditEvent({ principal, ...policy, outcome: 'denied', reason: error?.code, requestId }));
    error.requestId = requestId;
    throw error;
  }
}

function authorizationResponse(response, error) {
  return response.status(Number.isInteger(error?.status) ? error.status : 401).json({
    error: error?.message || 'Unauthorized',
    code: error?.code || 'unauthorized',
    requestId: error?.requestId,
  });
}

module.exports = { authorizationResponse, authorizeRequest };
