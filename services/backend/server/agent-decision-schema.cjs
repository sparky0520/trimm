'use strict';

const operationSchema = {
  type: 'object',
  required: ['id', 'type', 'enabled', 'confidence', 'evidenceIds'],
  properties: {
    id: { type: 'string' },
    type: {
      type: 'string',
      enum: [
        'remove_fumbles', 'set_audio_cleanup', 'set_audio_levels',
        'add_sound_effect', 'set_background_music', 'add_captions', 'semantic_visual',
      ],
    },
    enabled: { type: 'boolean' },
    confidence: { type: 'number', minimum: 0, maximum: 1 },
    evidenceIds: { type: 'array', minItems: 1, items: { type: 'string' } },
  },
  additionalProperties: true,
};

const evidenceRequestBase = {
  sourceId: { type: 'string' },
  startMs: { type: 'integer', minimum: 0 },
  endMs: { type: 'integer', minimum: 1 },
  maximumBytes: { type: 'integer', minimum: 1 },
  maximumDurationMs: { type: 'integer', minimum: 1 },
  maximumCount: { type: 'integer', minimum: 1 },
  estimatedCostMicros: { type: 'integer', minimum: 0 },
};

function evidenceRequestVariant(type, purposes, formats) {
  return {
    type: 'object',
    required: ['sourceId', 'type', 'purpose', 'acceptedFormat', 'maximumBytes'],
    properties: {
      ...evidenceRequestBase,
      type: { type: 'string', enum: [type] },
      purpose: { type: 'string', enum: purposes },
      acceptedFormat: { type: 'string', enum: formats },
    },
    additionalProperties: false,
  };
}

// Keep provider-side structure intentionally smaller than the executable contract.
// Gemini constrains the envelope here; the deterministic validators remain the
// authority for evidence, ranges, operation semantics, privacy and project revision.
const AGENT_DECISION_SCHEMA = Object.freeze({
  anyOf: [
    {
      type: 'object',
      required: ['type', 'requests'],
      properties: {
        type: { type: 'string', enum: ['evidence_request'] },
        requests: {
          type: 'array',
          minItems: 1,
          items: {
            anyOf: [
              evidenceRequestVariant(
                'audio',
                ['transcription', 'speech-analysis', 'audio-analysis'],
                ['audio/mpeg', 'audio/mp4', 'audio/ogg'],
              ),
              evidenceRequestVariant(
                'frame',
                ['visual-analysis', 'shot-analysis', 'planner-evidence'],
                ['image/jpeg', 'image/webp'],
              ),
              evidenceRequestVariant(
                'crop',
                ['ocr', 'ui-analysis', 'planner-evidence'],
                ['image/jpeg', 'image/webp'],
              ),
              evidenceRequestVariant(
                'text',
                ['planning', 'transcript-analysis'],
                ['text/plain', 'application/json'],
              ),
              evidenceRequestVariant(
                'evidence',
                ['planning', 'candidate-evaluation'],
                ['application/json'],
              ),
            ],
          },
        },
      },
      additionalProperties: false,
    },
    {
      type: 'object',
      required: ['type', 'expectedRevision', 'summary', 'operations'],
      properties: {
        type: { type: 'string', enum: ['edit_program'] },
        expectedRevision: { type: 'integer', minimum: 0 },
        summary: { type: 'string' },
        operations: { type: 'array', items: operationSchema },
      },
      additionalProperties: false,
    },
    {
      type: 'object',
      required: ['type', 'question', 'options'],
      properties: {
        type: { type: 'string', enum: ['clarification'] },
        question: { type: 'string' },
        options: { type: 'array', items: { type: 'string' } },
      },
      additionalProperties: false,
    },
    {
      type: 'object',
      required: ['type', 'reason'],
      properties: {
        type: { type: 'string', enum: ['fallback'] },
        reason: { type: 'string' },
      },
      additionalProperties: false,
    },
  ],
});

module.exports = { AGENT_DECISION_SCHEMA };
