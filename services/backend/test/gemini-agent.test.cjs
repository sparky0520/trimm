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
});
