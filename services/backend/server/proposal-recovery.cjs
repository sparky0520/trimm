'use strict';

const MAX_PROPOSAL_BYTES = 64 * 1024;
const FINISH_REASONS_COMPLETE = new Set(['STOP', 'MAX_TOKENS_UNSPECIFIED', undefined, null]);
const PRIVATE_FIELD = /("(?:apiKey|api_key|token|secret|password|reasoning|thought|chainOfThought|chain_of_thought)"\s*:\s*)"(?:[^"\\]|\\.)*"/gi;
const NON_REPAIRABLE_CODES = new Set([
  'cloud_boundary_violation',
  'evidence_budget_exceeded',
  'invalid_evidence_reference',
  'invalid_evidence_range',
  'proposal_too_large',
  'provider_truncated_proposal',
  'repair_changed_editorial_intent',
  'stale_revision',
  'unknown_operation',
  'unsupported_evidence_request',
]);

function redactPrivateFields(value) {
  return value.replace(PRIVATE_FIELD, '$1"[redacted]"');
}

function safeError(error) {
  return {
    code: typeof error?.code === 'string' ? error.code : 'invalid_planner_response',
    message: typeof error?.message === 'string' ? error.message.slice(0, 500) : 'Proposal validation failed.',
  };
}

function boundedAttempt(rawText, finishReason, maximumBytes = MAX_PROPOSAL_BYTES, usage) {
  const text = redactPrivateFields(typeof rawText === 'string' ? rawText : JSON.stringify(rawText));
  const bytes = Buffer.byteLength(text || '');
  let stored = text || '';
  let truncated = false;
  if (bytes > maximumBytes) {
    stored = Buffer.from(stored).subarray(0, maximumBytes).toString('utf8');
    truncated = true;
  }
  return {
    rawText: stored,
    originalBytes: bytes,
    truncated,
    providerTruncated: !FINISH_REASONS_COMPLETE.has(finishReason),
    finishReason: typeof finishReason === 'string' ? finishReason.slice(0, 100) : null,
    ...(usage ? { usage } : {}),
  };
}

function candidate(value, maximumBytes) {
  if (value && typeof value === 'object' && typeof value.rawText === 'string') {
    return boundedAttempt(value.rawText, value.finishReason, maximumBytes, value.usage);
  }
  return boundedAttempt(typeof value === 'string' ? value : JSON.stringify(value), null, maximumBytes);
}

function parseAttempt(attempt) {
  if (attempt.truncated) {
    throw Object.assign(new Error('Planner output exceeded the bounded proposal size.'), {
      status: 502, code: 'proposal_too_large',
    });
  }
  if (attempt.providerTruncated) {
    throw Object.assign(new Error('The provider truncated the proposal.'), {
      status: 502, code: 'provider_truncated_proposal',
    });
  }
  try {
    return JSON.parse(attempt.rawText);
  } catch {
    throw Object.assign(new Error('Planner returned broken JSON.'), {
      status: 502, code: 'invalid_proposal_json',
    });
  }
}

function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]));
}

function editorialSignature(value) {
  if (!value || typeof value !== 'object') return {};
  const signature = {};
  if (typeof value.type === 'string') signature.type = value.type;
  if (Number.isSafeInteger(value.expectedRevision)) signature.expectedRevision = value.expectedRevision;
  if (typeof value.summary === 'string') signature.summary = value.summary;
  if (typeof value.question === 'string') signature.question = value.question;
  if (typeof value.reason === 'string') signature.reason = value.reason;
  if (Array.isArray(value.operations)) signature.operations = value.operations.map((operation) => {
    if (!operation || typeof operation !== 'object') return {};
    const fields = {};
    for (const key of ['id', 'type', 'intent', 'treatment']) {
      if (typeof operation[key] === 'string') fields[key] = operation[key];
    }
    if (typeof operation.enabled === 'boolean') fields.enabled = operation.enabled;
    for (const key of ['ranges', 'reasons', 'evidenceIds']) {
      if (Array.isArray(operation[key])) fields[key] = stable(operation[key]);
    }
    for (const key of ['range', 'subject', 'framing']) {
      if (operation[key] && typeof operation[key] === 'object') fields[key] = stable(operation[key]);
    }
    return fields;
  });
  if (Array.isArray(value.requests)) signature.requests = value.requests.map((request) => stable(request));
  return stable(signature);
}

function containsEditorialValue(actual, expected) {
  if (Array.isArray(expected)) {
    return Array.isArray(actual) && actual.length === expected.length &&
      expected.every((value, index) => containsEditorialValue(actual[index], value));
  }
  if (expected && typeof expected === 'object') {
    return actual && typeof actual === 'object' &&
      Object.entries(expected).every(([key, value]) => containsEditorialValue(actual[key], value));
  }
  return Object.is(actual, expected);
}

function assertSameEditorialIntent(original, repaired) {
  const before = editorialSignature(original);
  if (Object.keys(before).length && !containsEditorialValue(editorialSignature(repaired), before)) {
    throw Object.assign(new Error('The repaired proposal changed editorial intent.'), {
      status: 422, code: 'repair_changed_editorial_intent',
    });
  }
}

class ProposalRecoveryError extends Error {
  constructor(message, recovery) {
    super(message);
    this.name = 'ProposalRecoveryError';
    this.status = 422;
    this.code = 'proposal_repair_failed';
    this.recovery = recovery;
  }
}

async function recoverProposal({
  primary,
  repair,
  validate,
  originalRequest,
  editorialIntent,
  maximumBytes = MAX_PROPOSAL_BYTES,
}) {
  const first = candidate(primary, maximumBytes);
  let original;
  let primaryError;
  try {
    original = parseAttempt(first);
    const decision = validate(original);
    return { decision, recovery: null, usage: [first.usage].filter(Boolean) };
  } catch (error) {
    primaryError = error;
    first.validationErrors = [safeError(error)];
  }

  if (typeof repair !== 'function') {
    throw primaryError;
  }
  if (NON_REPAIRABLE_CODES.has(primaryError?.code)) {
    throw primaryError;
  }

  let repairedResponse;
  try {
    repairedResponse = await repair({
      malformedResponse: first.rawText,
      responseTruncated: first.truncated,
      originalBytes: first.originalBytes,
      validationErrors: first.validationErrors,
      expectedSchema: 'ctrlB agent decision contract v1',
      originalRequest,
      editorialIntent,
      instruction: 'Correct structure only. Preserve the original editorial decision exactly.',
    });
  } catch (error) {
    throw new ProposalRecoveryError('The repair model call failed.', {
      status: 'repair_failed', attempts: [first], repairError: safeError(error), canRetry: true, canClarify: true,
    });
  }

  const second = candidate(repairedResponse, maximumBytes);
  try {
    const repaired = parseAttempt(second);
    if (!original) {
      try { original = JSON.parse(first.rawText); } catch {}
    }
    assertSameEditorialIntent(original, repaired);
    const decision = validate(repaired);
    return {
      decision,
      recovery: { status: 'repaired', attempts: [first, second], canRetry: false, canClarify: false },
      usage: [first.usage, second.usage].filter(Boolean),
    };
  } catch (error) {
    second.validationErrors = [safeError(error)];
    throw new ProposalRecoveryError('The repaired proposal is still not executable.', {
      status: 'repair_failed', attempts: [first, second], canRetry: true, canClarify: true,
    });
  }
}

module.exports = {
  MAX_PROPOSAL_BYTES,
  ProposalRecoveryError,
  assertSameEditorialIntent,
  boundedAttempt,
  editorialSignature,
  redactPrivateFields,
  recoverProposal,
};
