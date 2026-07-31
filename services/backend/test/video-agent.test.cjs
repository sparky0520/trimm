'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createGeminiDecisionProvider } = require('../server/gemini-agent.cjs');
const { validateVideoToolCall } = require('../server/layer2-orchestrator.cjs');
const { AgentRuns } = require('../server/agent-runs.cjs');

const inventory = {
  sources: [{ id: 'source-1', durationMs: 120_000 }], observations: [],
};

test('Gemini receives video directly at the requested media resolution and exposes one tool', async () => {
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
  assert.equal(result.toolCall.name, 'inspect_video_range');
  assert.equal(request.contents[0].parts[0].inlineData.mimeType, 'video/mp4');
  assert.equal(request.contents[0].parts[0].mediaResolution.level, 'MEDIA_RESOLUTION_LOW');
  assert.deepEqual(request.config.tools[0].functionDeclarations.map((item) => item.name), ['inspect_video_range']);
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
    contentKey: 'content-1', bytes: Buffer.from('video'), providerFile: {
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
