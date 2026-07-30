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
            type: 'object',
            required: ['sourceId', 'type', 'purpose', 'acceptedFormat', 'maximumBytes'],
            properties: {
              sourceId: { type: 'string' },
              type: { type: 'string', enum: ['audio', 'frame', 'crop', 'text', 'evidence'] },
              purpose: { type: 'string' },
              acceptedFormat: { type: 'string' },
              startMs: { type: 'integer', minimum: 0 },
              endMs: { type: 'integer', minimum: 1 },
              maximumBytes: { type: 'integer', minimum: 1 },
              maximumDurationMs: { type: 'integer', minimum: 1 },
              maximumCount: { type: 'integer', minimum: 1 },
              estimatedCostMicros: { type: 'integer', minimum: 0 },
            },
            additionalProperties: false,
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
