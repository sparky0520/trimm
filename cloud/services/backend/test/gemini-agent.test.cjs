'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createGeminiDecisionProvider } = require('../server/gemini-agent.cjs');

test('new agent path is pinned to Gemini 3.6 Flash and configures tools', async () => {
  let request;
  const decide = createGeminiDecisionProvider({
    model: 'gemini-3.6-flash',
    client: { models: { generateContent: async (value) => {
      request = value;
      return {
        functionCalls: [{ name: 'finish_without_proposal', args: { reason: 'More evidence is needed.' } }],
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
  assert.deepEqual(request.config.tools[0].functionDeclarations.map((item) => item.name), [
    'inspect_video_range', 'propose_remove_ranges',
    'propose_set_dialogue_loudness', 'propose_set_music_ducking',
    'propose_add_captions', 'propose_set_aspect_ratio', 'propose_add_text_overlay', 'propose_add_section_transition', 'propose_adjust_exposure', 'propose_adjust_temperature', 'propose_adjust_contrast', 'propose_adjust_saturation',
    'propose_adjust_highlights', 'propose_adjust_shadows', 'propose_adjust_skin_protection',
    'propose_add_bgm', 'propose_add_sfx',
    'ask_clarification', 'finalize_edit_program', 'remove_draft_operation', 'finish_without_proposal',
  ]);
  assert.deepEqual(decision.toolCalls, [{ name: 'finish_without_proposal', args: { reason: 'More evidence is needed.' } }]);
  assert.equal(decision.usage.totalTokens, 1300);
  assert.equal(decision.usage.estimatedCostMicros, 2250);

  assert.deepEqual(decide.capabilities, {
    nativeVideoAudio: true,
    timestampGrounding: true,
    toolUse: true,
    structuredOutput: true,
    reusableCachedContext: false,
    primaryModel: 'gemini-3.6-flash',
  });
  assert.strictEqual(request.config.maxOutputTokens, 4096);
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
