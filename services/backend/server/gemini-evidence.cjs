'use strict';

const { GoogleGenAI } = require('@google/genai');

const EVIDENCE_SCHEMA = Object.freeze({
  type: 'object',
  additionalProperties: false,
  required: ['summary', 'observations'],
  properties: {
    summary: { type: 'string', maxLength: 1200 },
    transcript: { type: ['string', 'null'], maxLength: 6000 },
    observations: {
      type: 'array',
      maxItems: 40,
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['description', 'confidence'],
        properties: {
          description: { type: 'string', maxLength: 500 },
          confidence: { type: 'number', minimum: 0, maximum: 1 },
          startMs: { type: ['integer', 'null'], minimum: 0 },
          endMs: { type: ['integer', 'null'], minimum: 0 },
        },
      },
    },
  },
});

function boundedResult(value) {
  if (!value || typeof value !== 'object' || typeof value.summary !== 'string' || !Array.isArray(value.observations)) {
    throw Object.assign(new Error('Gemini returned invalid evidence.'), { status: 502, code: 'invalid_evidence_response' });
  }
  return {
    summary: value.summary.slice(0, 1200),
    transcript: typeof value.transcript === 'string' ? value.transcript.slice(0, 6000) : null,
    observations: value.observations.slice(0, 40).map((item) => ({
      description: String(item?.description || '').slice(0, 500),
      confidence: Math.max(0, Math.min(1, Number(item?.confidence) || 0)),
      startMs: Number.isSafeInteger(item?.startMs) && item.startMs >= 0 ? item.startMs : null,
      endMs: Number.isSafeInteger(item?.endMs) && item.endMs >= 0 ? item.endMs : null,
    })),
  };
}

function createGeminiEvidenceAnalyzer({
  apiKey = process.env.GEMINI_API_KEY,
  model = process.env.GEMINI_MODEL || 'gemini-3.6-flash',
  client,
} = {}) {
  const ai = client || (apiKey ? new GoogleGenAI({ apiKey }) : null);
  return async ({ job, bytes }) => {
    if (!ai) throw Object.assign(new Error('GEMINI_API_KEY is not configured'), { status: 503, code: 'gemini_unconfigured' });
    const reference = job.timelineReferences[0];
    const response = await ai.models.generateContent({
      model,
      contents: [{
        role: 'user',
        parts: [
          {
            text: `Analyze this selectively authorized ${job.type} derivative for ${job.purpose}.
It maps exactly to source ${reference.sourceId}, ${reference.startMs}-${reference.endMs} ms.
Return concise, directly observable evidence only. For audio, transcribe speech and timestamp useful observations.
For an image, describe visible composition, subjects, quality, and editing-relevant facts. Do not infer identity or hidden context.`,
          },
          { inlineData: { mimeType: job.acceptedFormat, data: bytes.toString('base64') } },
        ],
      }],
      config: {
        responseMimeType: 'application/json',
        responseJsonSchema: EVIDENCE_SCHEMA,
        temperature: 0.1,
      },
    });
    if (!response.text) throw Object.assign(new Error('Gemini returned no evidence.'), { status: 502, code: 'empty_evidence_response' });
    return boundedResult(JSON.parse(response.text));
  };
}

module.exports = { EVIDENCE_SCHEMA, boundedResult, createGeminiEvidenceAnalyzer };
