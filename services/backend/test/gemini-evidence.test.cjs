'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createGeminiEvidenceAnalyzer } = require('../server/gemini-evidence.cjs');

test('Gemini evidence analysis sends only the authorized derivative and returns bounded observations', async () => {
  let request;
  const analyze = createGeminiEvidenceAnalyzer({
    client: { models: { generateContent: async (value) => {
      request = value;
      return { text: JSON.stringify({
        summary: 'Clear spoken instruction.',
        transcript: 'Open the settings panel.',
        observations: [{ description: 'Speaker gives one instruction.', confidence: 0.9, startMs: 10, endMs: 900 }],
      }) };
    } } },
  });
  const value = await analyze({
    job: {
      type: 'audio', purpose: 'speech-analysis', acceptedFormat: 'audio/mp4',
      timelineReferences: [{ sourceId: 'clip-1', startMs: 1000, endMs: 2000 }],
    },
    bytes: Buffer.from('selective-audio'),
  });
  assert.equal(request.contents[0].parts[1].inlineData.mimeType, 'audio/mp4');
  assert.equal(Buffer.from(request.contents[0].parts[1].inlineData.data, 'base64').toString(), 'selective-audio');
  assert.equal(value.transcript, 'Open the settings panel.');
  assert.equal(value.observations[0].startMs, 10);
});
