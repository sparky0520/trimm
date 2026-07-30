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
      return { text: '{"type":"fallback","reason":"More evidence is needed."}' };
    } } },
  });
  const decision = await decide({ systemPolicy: 'policy', inventory: { projectId: 'p' }, evidence: [] });
  assert.equal(request.model, 'gemini-3.6-flash');
  assert.equal(request.config.responseMimeType, 'application/json');
  assert.ok(Array.isArray(request.config.responseJsonSchema.anyOf));
  assert.deepEqual(
    request.config.responseJsonSchema.anyOf.map((variant) => variant.properties.type.enum[0]),
    ['evidence_request', 'edit_program', 'clarification', 'fallback'],
  );
  const evidenceVariants = request.config.responseJsonSchema.anyOf[0]
    .properties.requests.items.anyOf;
  assert.deepEqual(
    evidenceVariants.map((variant) => variant.properties.type.enum[0]),
    ['audio', 'frame', 'crop', 'text', 'evidence'],
  );
  assert.deepEqual(
    evidenceVariants[0].properties.acceptedFormat.enum,
    ['audio/mp4'],
  );
  assert.deepEqual(JSON.parse(decision.rawText), { type: 'fallback', reason: 'More evidence is needed.' });
  assert.equal(typeof decide.repair, 'function');
  assert.equal(typeof decide.escalate, 'function');
  assert.deepEqual(decide.capabilities, {
    nativeVideoAudio: true,
    timestampGrounding: true,
    toolUse: true,
    structuredOutput: true,
    reusableCachedContext: true,
    primaryModel: 'gemini-3.6-flash',
    escalationModel: 'gemini-3.1-pro-preview',
  });

  await decide.escalate({
    systemPolicy: 'policy', inventory: { projectId: 'p' }, evidence: [],
    cachedContent: 'cachedContents/project-1', tools: [{ functionDeclarations: [] }],
  });
  assert.equal(request.model, 'gemini-3.1-pro-preview');
  assert.equal(request.config.cachedContent, 'cachedContents/project-1');
  assert.equal(request.config.tools.length, 1);

  await decide.repair({
    expectedSchema: 'ctrlB agent decision contract v1',
    malformedResponse: '{"type":"edit_program"}',
    validationErrors: [{ code: 'invalid_edit_program' }],
  });
  assert.equal(request.config.responseMimeType, 'application/json');
  assert.ok(Array.isArray(request.config.responseJsonSchema.anyOf));
  assert.equal(request.config.temperature, 0);
});
