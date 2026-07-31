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
  assert.deepEqual(request.config.tools[0].functionDeclarations.map((tool) => tool.name), ['inspect_video_range']);
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
