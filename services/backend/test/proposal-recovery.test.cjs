'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  MAX_PROPOSAL_BYTES,
  ProposalRecoveryError,
  recoverProposal,
  boundedAttempt,
} = require('../server/proposal-recovery.cjs');

function validate(value) {
  if (!value || value.type !== 'edit_program') throw Object.assign(new Error('Missing edit program type.'), { code: 'invalid_type' });
  if (value.expectedRevision !== 4) throw Object.assign(new Error('Stale revision.'), { code: 'stale_revision' });
  if (!Array.isArray(value.operations)) throw Object.assign(new Error('Missing operations.'), { code: 'missing_fields' });
  for (const operation of value.operations) {
    if (!['remove_fumbles', 'set_audio_cleanup'].includes(operation.type)) {
      throw Object.assign(new Error('Unknown operation.'), { code: 'unknown_operation' });
    }
    if (typeof operation.enabled !== 'boolean') throw Object.assign(new Error('Wrong enabled type.'), { code: 'wrong_type' });
    if (!operation.evidenceIds?.every((id) => id === 'evidence-1')) {
      throw Object.assign(new Error('Invalid evidence reference.'), { code: 'invalid_evidence_reference' });
    }
  }
  return value;
}

const valid = {
  type: 'edit_program',
  expectedRevision: 4,
  summary: 'Remove the repeated phrase.',
  operations: [{
    id: 'op-1', type: 'remove_fumbles', enabled: true, evidenceIds: ['evidence-1'],
  }],
};

test('broken JSON is structurally repaired and both bounded attempts are retained', async () => {
  let request;
  const result = await recoverProposal({
    primary: { rawText: '{"type":"edit_program",', finishReason: 'STOP' },
    repair: async (value) => { request = value; return JSON.stringify(valid); },
    validate,
    originalRequest: { projectId: 'project-1' },
    editorialIntent: 'Remove the repeated phrase',
  });
  assert.equal(result.decision.type, 'edit_program');
  assert.equal(result.recovery.status, 'repaired');
  assert.equal(result.recovery.attempts.length, 2);
  assert.equal(request.instruction.includes('structure only'), true);
  assert.deepEqual(request.validationErrors.map(({ code }) => code), ['invalid_proposal_json']);
});

test('missing fields and wrong types can be corrected without changing known editorial fields', async () => {
  const malformed = {
    ...valid,
    operations: [{ id: 'op-1', type: 'remove_fumbles', enabled: 'true', evidenceIds: ['evidence-1'] }],
  };
  const result = await recoverProposal({
    primary: malformed,
    repair: async () => valid,
    validate,
    originalRequest: {},
    editorialIntent: 'same',
  });
  assert.equal(result.decision.operations[0].enabled, true);
});

test('terminal semantic failures do not spend a repair call', async () => {
  for (const [malformed, code] of [
    [{ ...valid, operations: [{ ...valid.operations[0], type: 'invented_operation' }] }, 'unknown_operation'],
    [{ ...valid, expectedRevision: 3 }, 'stale_revision'],
    [{ ...valid, operations: [{ ...valid.operations[0], evidenceIds: ['invented-evidence'] }] }, 'invalid_evidence_reference'],
  ]) {
    let repairCalled = false;
    await assert.rejects(recoverProposal({
      primary: malformed,
      repair: async () => { repairCalled = true; return malformed; },
      validate,
      originalRequest: {},
      editorialIntent: 'same',
    }), (error) => error.code === code);
    assert.equal(repairCalled, false);
  }
});

test('provider truncation and oversized output stop without spending a repair call', async () => {
  for (const [primary, code] of [
    [{ rawText: JSON.stringify(valid), finishReason: 'MAX_TOKENS' }, 'provider_truncated_proposal'],
    [{ rawText: `{"padding":"${'x'.repeat(MAX_PROPOSAL_BYTES)}"}`, finishReason: 'STOP' }, 'proposal_too_large'],
  ]) {
    let repairCalled = false;
    await assert.rejects(recoverProposal({
      primary,
      repair: async () => { repairCalled = true; return valid; },
      validate,
      originalRequest: {},
      editorialIntent: 'same',
    }), (error) => error.code === code);
    assert.equal(repairCalled, false);
  }
});

test('a structurally valid repair is rejected when it changes editorial intent', async () => {
  const missingEnabled = {
    ...valid,
    operations: [{ id: 'op-1', type: 'remove_fumbles', evidenceIds: ['evidence-1'] }],
  };
  const changed = {
    ...valid,
    operations: [{ id: 'op-1', type: 'set_audio_cleanup', enabled: true, evidenceIds: ['evidence-1'] }],
  };
  await assert.rejects(recoverProposal({
    primary: missingEnabled,
    repair: async () => changed,
    validate,
    originalRequest: {},
    editorialIntent: 'same',
  }), (error) => error.code === 'proposal_repair_failed' &&
    error.recovery.attempts[1].validationErrors[0].code === 'repair_changed_editorial_intent');
});

test('secrets and hidden reasoning are redacted before persistence or repair', () => {
  const attempt = boundedAttempt('{"apiKey":"secret-key","reasoning":"private thought","type":"fallback"}');
  assert.equal(attempt.rawText.includes('secret-key'), false);
  assert.equal(attempt.rawText.includes('private thought'), false);
  assert.equal(attempt.rawText.match(/\[redacted\]/g).length, 2);
});
