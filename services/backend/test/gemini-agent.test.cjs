'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createGeminiDecisionProvider } = require('../server/gemini-agent.cjs');

test('new agent path is pinned to Gemini 3.6 Flash and requests JSON', async () => {
  let request;
  const decide = createGeminiDecisionProvider({
    model: 'gemini-3.6-flash',
    client: { models: { generateContent: async (value) => {
      request = value;
      return {
        text: '{"type":"fallback","reason":"More evidence is needed."}',
        responseId: 'response-1',
        usageMetadata: {
          promptTokenCount: 1000,
          candidatesTokenCount: 100,
          thoughtsTokenCount: 200,
          totalTokenCount: 1300,
        },
      };
    } } },
  });
  const decision = await decide({ systemPolicy: 'policy', inventory: { projectId: 'p' }, evidence: [] });
  assert.equal(request.model, 'gemini-3.6-flash');
  assert.strictEqual(request.config.responseMimeType, 'application/json');
  assert.deepStrictEqual(request.config.tools[0].functionDeclarations.map((decl) => decl.name), [
    'inspect_video_range', 'propose_remove_ranges',
    'propose_set_dialogue_loudness', 'propose_set_music_ducking',
    'propose_add_captions', 'propose_vfx', 'propose_adjust_exposure', 'propose_adjust_temperature', 'propose_adjust_contrast', 'propose_adjust_saturation',
    'propose_adjust_highlights', 'propose_adjust_shadows', 'propose_adjust_skin_protection',
    'ask_clarification', 'finalize_edit_program', 'remove_draft_operation', 'finish_without_proposal',
  ]);
  assert.deepEqual(JSON.parse(decision.rawText), { type: 'fallback', reason: 'More evidence is needed.' });
  assert.equal(decision.usage.totalTokens, 1300);
  assert.equal(decision.usage.estimatedCostMicros, 3750);
  assert.equal(typeof decide.repair, 'function');
  assert.deepEqual(decide.capabilities, {
    nativeVideoAudio: true,
    timestampGrounding: true,
    toolUse: true,
    structuredOutput: true,
    reusableCachedContext: false,
    primaryModel: 'gemini-3.6-flash',
  });
  assert.strictEqual(request.config.maxOutputTokens, 4096);

  await decide.repair({
    expectedSchema: 'ctrlB agent decision contract v1',
  });
});

test('gemini agent throws structured error when evidence bytes are missing', async () => {
  const decide = createGeminiDecisionProvider({
    model: 'gemini-3.6-flash',
    client: { models: { generateContent: async () => ({ text: '{}' }) } },
  });
  await assert.rejects(
    () => decide({ systemPolicy: 'policy', inventory: { projectId: 'p' }, evidence: [{ id: 'job-1', resolution: 'LOW' }] }),
    (error) => error.code === 'evidence_bytes_missing' && error.status === 409
  );
});
