'use strict';

const observationSchema = {
  type: 'object', additionalProperties: false,
  required: ['id', 'sourceId', 'startMs', 'endMs', 'description', 'confidence', 'resolution'],
  properties: {
    id: { type: 'string', pattern: '^[A-Za-z0-9._:-]{1,200}$' },
    sourceId: { type: 'string' },
    startMs: { type: 'integer', minimum: 0 },
    endMs: { type: 'integer', minimum: 1 },
    description: { type: 'string', maxLength: 500 },
    confidence: { type: 'number', minimum: 0, maximum: 1 },
    resolution: { type: 'string', enum: ['LOW', 'HIGH'] },
  },
};

const operationSchema = {
  type: 'object',
  required: ['id', 'type', 'enabled', 'confidence', 'evidenceIds'],
  properties: {
    id: { type: 'string' },
    type: { type: 'string', enum: [
      'remove_fumbles', 'set_audio_cleanup', 'set_audio_levels',
      'add_sound_effect', 'set_background_music', 'add_captions', 'semantic_visual',
    ] },
    enabled: { type: 'boolean' },
    confidence: { type: 'number', minimum: 0, maximum: 1 },
    evidenceIds: { type: 'array', minItems: 1, items: { type: 'string' } },
  },
  additionalProperties: true,
};

const observations = { type: 'array', maxItems: 100, items: observationSchema };

const AGENT_DECISION_SCHEMA = Object.freeze({
  anyOf: [
    {
      type: 'object', additionalProperties: false,
      required: ['type', 'expectedRevision', 'summary', 'operations', 'observations'],
      properties: {
        type: { type: 'string', enum: ['edit_program'] },
        expectedRevision: { type: 'integer', minimum: 0 },
        summary: { type: 'string' },
        operations: { type: 'array', items: operationSchema }, observations,
      },
    },
    {
      type: 'object', additionalProperties: false,
      required: ['type', 'question', 'options', 'observations'],
      properties: {
        type: { type: 'string', enum: ['clarification'] }, question: { type: 'string' },
        options: { type: 'array', items: { type: 'string' } }, observations,
      },
    },
    {
      type: 'object', additionalProperties: false,
      required: ['type', 'reason', 'observations'],
      properties: { type: { type: 'string', enum: ['fallback'] }, reason: { type: 'string' }, observations },
    },
  ],
});

const INSPECT_VIDEO_RANGE_TOOL = Object.freeze({
  name: 'inspect_video_range',
  description: 'Upload and inspect a source video range. Use LOW normally; use HIGH only when an overlapping LOW inspection left a specific uncertainty.',
  parametersJsonSchema: {
    type: 'object', additionalProperties: false,
    required: ['sourceId', 'startMs', 'endMs', 'resolution', 'uncertainty'],
    properties: {
      sourceId: { type: 'string' }, startMs: { type: 'integer', minimum: 0 },
      endMs: { type: 'integer', minimum: 1 }, resolution: { type: 'string', enum: ['LOW', 'HIGH'] },
      uncertainty: { type: 'string', maxLength: 500 },
    },
  },
});

module.exports = { AGENT_DECISION_SCHEMA, INSPECT_VIDEO_RANGE_TOOL };
