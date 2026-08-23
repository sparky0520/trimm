'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createGeminiDecisionProvider } = require('../server/gemini-agent.cjs');
const { Layer2Orchestrator, validateDecision, validateVideoToolCall } = require('../server/layer2-orchestrator.cjs');
const { AgentRuns } = require('../server/agent-runs.cjs');

const inventory = {
  sources: [{ id: 'source-1', durationMs: 120_000 }], observations: [],
  budgets: { maximumDurationMs: 600_000, maximumBytes: 8_000_000, retentionMs: 86_400_000 },
};

test('Gemini receives video directly and exposes evidence and focused proposal tools', async () => {
  let request;
  const decide = createGeminiDecisionProvider({ client: { models: { generateContent: async (value) => {
    request = value;
    return { functionCalls: [{ name: 'inspect_video_range', args: {
      sourceId: 'source-1', startMs: 30_000, endMs: 60_000, resolution: 'LOW', uncertainty: '',
    } }], usageMetadata: { promptTokenCount: 100, candidatesTokenCount: 10 } };
  } } } });
  const result = await decide({ systemPolicy: 'policy', inventory: {}, evidence: [{
    id: 'job-1', sourceId: 'source-1', startMs: 0, endMs: 30_000,
    resolution: 'LOW', bytes: Buffer.from('video'),
  }] });
  assert.equal(result.toolCalls[0].name, 'inspect_video_range');
  assert.equal(request.contents[0].parts[0].inlineData.mimeType, 'video/mp4');
  assert.deepEqual(request.config.tools[0].functionDeclarations.map((item) => item.name), [
    'inspect_video_range', 'propose_remove_ranges',
    'propose_set_dialogue_loudness', 'propose_set_music_ducking',
    'propose_add_captions', 'propose_set_aspect_ratio', 'propose_add_text_overlay', 'propose_adjust_exposure', 'propose_adjust_temperature', 'propose_adjust_contrast', 'propose_adjust_saturation',
    'propose_adjust_highlights', 'propose_adjust_shadows', 'propose_adjust_skin_protection',
    'ask_clarification', 'finalize_edit_program', 'remove_draft_operation', 'finish_without_proposal',
  ]);
});

test('a persisted Gemini file reference is reused after creating a new provider instance', async () => {
  let request;
  const decide = createGeminiDecisionProvider({ client: {
    files: { upload: async () => { throw new Error('must not upload'); } },
    models: { generateContent: async (value) => {
      request = value;
      return { text: '{"type":"fallback","reason":"done","observations":[]}', usageMetadata: {} };
    } },
  } });
  await decide({ systemPolicy: 'policy', inventory: {}, evidence: [{
    id: 'job-1', sourceId: 'source-1', startMs: 0, endMs: 30_000, resolution: 'LOW',
    bytes: Buffer.from('video'), providerFile: {
      provider: 'google-gemini', name: 'files/persisted',
      uri: 'https://generativelanguage.googleapis.com/v1beta/files/persisted', expiresAt: Date.now() + 60_000,
    },
  }] });
  assert.equal(request.contents[0].parts[0].fileData.fileUri,
    'https://generativelanguage.googleapis.com/v1beta/files/persisted');
});

test('video ranges are at most 30 seconds and HIGH requires overlapping LOW evidence', () => {
  const low = validateVideoToolCall({ name: 'inspect_video_range', args: {
    sourceId: 'source-1', startMs: 0, endMs: 30_000, resolution: 'LOW', uncertainty: '',
  } }, inventory, []);
  assert.equal(low.resolution, 'LOW');
  assert.throws(() => validateVideoToolCall({ name: 'inspect_video_range', args: {
    sourceId: 'source-1', startMs: 0, endMs: 30_001, resolution: 'LOW', uncertainty: '',
  } }, inventory, []), (error) => error.code === 'invalid_video_range');
  assert.throws(() => validateVideoToolCall({ name: 'inspect_video_range', args: {
    sourceId: 'source-1', startMs: 5_000, endMs: 10_000, resolution: 'HIGH', uncertainty: 'small text',
  } }, inventory, []), (error) => error.code === 'high_resolution_not_grounded');
  assert.equal(validateVideoToolCall({ name: 'inspect_video_range', args: {
    sourceId: 'source-1', startMs: 5_000, endMs: 10_000, resolution: 'HIGH', uncertainty: 'small text',
  } }, inventory, [{ sourceId: 'source-1', startMs: 0, endMs: 30_000, resolution: 'LOW' }]).resolution, 'HIGH');
});

test('removal proposals require source-bounded ranges covered by cited evidence', () => {
  const decisionInventory = {
    projectRevision: 3,
    sources: [{ id: 'source-1', durationMs: 120_000 }],
    observations: [{
      id: 'silence-1', sourceId: 'source-1', startMs: 10_000, endMs: 14_000,
      description: 'An awkward pause between clauses.', confidence: 0.9, resolution: 'LOW',
    }],
  };
  const valid = validateDecision({
    type: 'edit_program', expectedRevision: 3, summary: 'Tighten one awkward pause.', observations: [],
    operations: [{
      id: 'silence-op-1', type: 'remove_ranges', enabled: true, confidence: 0.9,
      evidenceIds: ['silence-1'], pacing: 'natural',
      ranges: [{ sourceId: 'source-1', startMs: 10_500, endMs: 13_500, evidenceIds: ['silence-1'] }],
    }],
  }, decisionInventory, []);
  assert.equal(valid.operations[0].type, 'remove_ranges');
  assert.throws(() => validateDecision({
    type: 'edit_program', expectedRevision: 3, summary: 'Invalid range.', observations: [],
    operations: [{
      id: 'silence-op-2', type: 'remove_ranges', enabled: true, confidence: 0.9,
      evidenceIds: ['silence-1'], pacing: 'natural',
      ranges: [{ sourceId: 'source-1', startMs: 9_000, endMs: 15_000, evidenceIds: ['silence-1'] }],
    }],
  }, decisionInventory, []), (error) => error.code === 'invalid_evidence_reference');
});

test('fine operations require an exact source interval covered by their cited evidence', () => {
  const decisionInventory = {
    projectRevision: 2, sources: [{ id: 'source-1', durationMs: 20_000 }],
    transcript: [], observations: [{ id: 'noise-1', sourceId: 'source-1', startMs: 2_000, endMs: 8_000,
      description: 'Dialogue is too quiet.', confidence: .9, resolution: 'LOW' }],
  };
  const operation = { id: 'noise-op', type: 'set_audio_levels', enabled: true, confidence: .9,
    evidenceIds: ['noise-1'], target: { sourceId: 'source-1', startMs: 3_000, endMs: 7_000 },
    parameter: 'dialogue_loudness', dialogueLufs: -16, reason: 'Dialogue is too quiet.' };
  assert.equal(validateDecision({ type: 'edit_program', expectedRevision: 2, summary: 'Clean one interval.',
    operations: [operation], observations: [] }, decisionInventory, []).operations[0].target.startMs, 3_000);
  assert.throws(() => validateDecision({ type: 'edit_program', expectedRevision: 2, summary: 'Too broad.',
    operations: [{ ...operation, target: { ...operation.target, startMs: 1_000 } }], observations: [] }, decisionInventory, []),
  (error) => error.code === 'invalid_evidence_reference');
});

test('short runs bootstrap video and continuations require exactly the requested job id', async () => {
  const orchestrator = {
    bootstrap: async () => ({ type: 'tool_request', tool: 'inspect_video_range', jobs: [{ id: 'job-1' }] }),
    orchestrate: async () => ({ type: 'fallback', reason: 'done', observations: [] }),
  };
  const runs = new AgentRuns({ orchestrator, id: () => 'run-1' });
  const request = { projectId: 'project-1', projectRevision: 1, budgets: {} };
  const started = await runs.start({ id: 'user-1' }, request);
  assert.equal(started.status, 'waiting_for_evidence');
  await assert.rejects(() => runs.continue({ id: 'user-1' }, 'run-1', { evidenceJobIds: ['other'] }),
    (error) => error.code === 'unexpected_evidence');
  const completed = await runs.continue({ id: 'user-1' }, 'run-1', { evidenceJobIds: ['job-1'] });
  assert.equal(completed.status, 'partial');
});

test('a follow-up message continues the same LangGraph-backed conversation', async () => {
  const received = [];
  const orchestrator = {
    bootstrap: async () => ({ type: 'clarification', question: 'Natural or tight?', options: ['natural', 'tight'], observations: [] }),
    orchestrate: async (_principal, value) => {
      received.push(value);
      return { type: 'fallback', reason: 'Updated', observations: [] };
    },
  };
  const runs = new AgentRuns({ orchestrator, id: () => 'chat-run' });
  const started = await runs.start({ id: 'user-1' }, {
    projectId: 'project-1', projectRevision: 1, intent: 'Remove awkward silences',
  });
  assert.equal(started.status, 'waiting_for_user');
  const continued = await runs.continue({ id: 'user-1' }, 'chat-run', { message: 'Keep it natural.' });
  assert.equal(continued.status, 'partial');
  assert.equal(received[0].intent, 'Keep it natural.');
  assert.deepEqual(received[0].messages.map((item) => item.role), ['user', 'assistant', 'user']);
});

test('the bounded agent loop returns tool results to Gemini before finalizing an atomic proposal', async () => {
  const calls = [];
  const decide = async () => ({
    toolCall: { name: 'propose_set_dialogue_loudness', args: {
      expectedRevision: 4, summary: 'Clean the dialogue.', observations: [],
      operation: { id: 'cleanup-1', enabled: true, confidence: .9, evidenceIds: ['obs-1'],
        sourceId: 'source-1', startMs: 0, endMs: 20_000, targetLufs: -16, reason: 'Too quiet.' },
    } }, history: [], usage: { callId: 'call-1' },
  });
  decide.toolResult = async ({ result }) => {
    calls.push(result);
    return { toolCall: { name: 'finalize_edit_program', args: {
      expectedRevision: 4, summary: 'Clean the dialogue.', operationIds: ['cleanup-1'],
    } }, history: [], usage: { callId: 'call-2' } };
  };
  const orchestrator = new Layer2Orchestrator({ derivativeJobs: {}, decide });
  const result = await orchestrator.orchestrate({ id: 'user-1' }, {
    projectId: 'project-1', deviceSessionId: 'device-1', projectRevision: 4,
    intent: 'Clean up the dialogue',
    sources: [{ id: 'source-1', fingerprint: 'a'.repeat(32), durationMs: 20_000, width: 1920, height: 1080, hasAudio: true }],
    transcript: [], evidenceJobIds: [], existingOperations: [],
    observations: [{ id: 'obs-1', sourceId: 'source-1', startMs: 0, endMs: 20_000, description: 'Dialogue is too quiet.', confidence: .9, resolution: 'LOW' }],
    budgets: {}, mediaConsent: { mode: 'video_analysis', grantedAt: new Date().toISOString(), disclosureVersion: 'v2' },
    supportedContractVersions: { agentRuns: [2], activityEvents: [2], videoToolRequests: [2], editPrograms: [2] },
  });
  assert.equal(calls[0].ok, true);
  assert.equal(result.type, 'edit_program');
  assert.equal(result.operations[0].type, 'set_audio_levels');
});

test('orchestrator correctly awaits async derivativeJobs readCompletedBytes (PostgresDerivativeJobs)', async () => {
  let passedEvidence = null;
  const asyncDerivativeJobs = {
    async read(id, principal, context) {
      return { id, status: 'completed', timelineReferences: [{ sourceId: 'source-1', startMs: 0, endMs: 10_000 }] };
    },
    async readCompletedBytes(id, principal, context) {
      return { job: { id }, bytes: Buffer.from('fake-mp4-bytes') };
    },
  };
  const decide = async ({ evidence }) => {
    passedEvidence = evidence;
    return { type: 'fallback', reason: 'done', observations: [] };
  };
  const orchestrator = new Layer2Orchestrator({ derivativeJobs: asyncDerivativeJobs, decide });
  await orchestrator.orchestrate({ id: 'user-1' }, {
    projectId: 'project-1', deviceSessionId: 'device-1', projectRevision: 1,
    intent: 'Analyze video',
    sources: [{ id: 'source-1', fingerprint: 'a'.repeat(32), durationMs: 20_000, width: 1920, height: 1080, hasAudio: true }],
    transcript: [], evidenceJobIds: ['11111111-2222-3333-4444-555555555555'], existingOperations: [],
    observations: [],
    budgets: {}, mediaConsent: { mode: 'video_analysis', grantedAt: new Date().toISOString(), disclosureVersion: 'v2' },
    supportedContractVersions: { agentRuns: [2], activityEvents: [2], videoToolRequests: [2], editPrograms: [2] },
  });
  assert.ok(passedEvidence);
  assert.equal(passedEvidence[0].id, '11111111-2222-3333-4444-555555555555');
  assert.ok(Buffer.isBuffer(passedEvidence[0].bytes));
  assert.equal(passedEvidence[0].bytes.toString(), 'fake-mp4-bytes');
});
