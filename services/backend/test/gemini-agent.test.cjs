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
  assert.equal(request.config.responseMimeType, 'application/json');
  assert.ok(Array.isArray(request.config.responseJsonSchema.anyOf));
  assert.deepEqual(
    request.config.responseJsonSchema.anyOf.map((variant) => variant.properties.type.enum[0]),
    ['edit_program', 'clarification', 'fallback'],
  );
  assert.deepEqual(request.config.tools[0].functionDeclarations.map((tool) => tool.name), [
    'inspect_video_range', 'search_sound_effects', 'search_background_music',
    'propose_remove_ranges',
    'propose_reduce_background_noise', 'propose_set_dialogue_loudness', 'propose_set_music_ducking',
    'propose_place_sound_effect', 'propose_place_background_music', 'propose_add_caption_cue',
    'propose_adjust_exposure', 'propose_adjust_temperature', 'propose_adjust_contrast', 'propose_adjust_saturation',
    'propose_adjust_highlights', 'propose_adjust_shadows', 'propose_adjust_skin_protection',
    'propose_add_visual_callout', 'propose_add_visual_spotlight', 'propose_reframe_subject',
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
  assert.equal(request.config.maxOutputTokens, 4096);
  assert.equal(request.config.thinkingConfig.thinkingLevel, 'LOW');

  await decide.repair({
    expectedSchema: 'ctrlB agent decision contract v1',
    malformedResponse: '{"type":"edit_program"}',
    validationErrors: [{ code: 'invalid_edit_program' }],
  });
  assert.equal(request.config.responseMimeType, 'application/json');
  assert.ok(Array.isArray(request.config.responseJsonSchema.anyOf));
  assert.equal(request.config.temperature, 0);
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
