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
    type: {
      type: 'string', enum: [
        'remove_fumbles', 'remove_awkward_silences', 'set_color_grade', 'set_audio_cleanup', 'set_audio_levels',
        'add_sound_effect', 'set_background_music', 'add_captions', 'semantic_visual',
      ]
    },
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
      required: ['type', 'questions', 'observations'],
      properties: {
        type: { type: 'string', enum: ['clarification'] },
        questions: {
          type: 'array', minItems: 1, maxItems: 3, items: {
            type: 'object', additionalProperties: false, required: ['id', 'prompt', 'options', 'recommendedOptionId'],
            properties: {
              id: { type: 'string' }, prompt: { type: 'string' }, recommendedOptionId: { type: 'string' },
              options: {
                type: 'array', minItems: 2, maxItems: 4, items: {
                  type: 'object', additionalProperties: false, required: ['id', 'label', 'description'],
                  properties: { id: { type: 'string' }, label: { type: 'string' }, description: { type: 'string' } },
                }
              },
            },
          }
        },
        observations,
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

const proposedObservation = {
  type: 'object', additionalProperties: false,
  required: ['id', 'sourceId', 'startMs', 'endMs', 'description', 'confidence', 'resolution'],
  properties: observationSchema.properties,
};

const proposalEnvelope = (operation) => ({
  type: 'object', additionalProperties: false,
  required: ['expectedRevision', 'summary', 'operation', 'observations'],
  properties: {
    expectedRevision: { type: 'integer', minimum: 0 },
    summary: { type: 'string', maxLength: 2000 },
    operation,
    observations: { type: 'array', maxItems: 100, items: proposedObservation },
  },
});

const range = {
  type: 'object', additionalProperties: false,
  required: ['sourceId', 'startMs', 'endMs', 'evidenceIds'],
  properties: {
    sourceId: { type: 'string' },
    startMs: { type: 'integer', minimum: 0 },
    endMs: { type: 'integer', minimum: 1 },
    evidenceIds: { type: 'array', minItems: 1, items: { type: 'string' } },
  },
};

const targetRangeProperties = {
  sourceId: { type: 'string' },
  startMs: { type: 'integer', minimum: 0 },
  endMs: { type: 'integer', minimum: 1 },
  reason: { type: 'string', minLength: 1, maxLength: 500 },
};

const PROPOSE_REMOVE_RANGES_TOOL = Object.freeze({
  name: 'propose_remove_ranges',
  description: `Propose removal of specific time ranges (such as speech fumbles, awkward silences, retakes, or unneeded content). Every range must be supported by supplied evidence artifacts. This tool constructs a proposal draft only; it never edits the project directly.`,
  parametersJsonSchema: proposalEnvelope({
    type: 'object', additionalProperties: false,
    required: ['id', 'enabled', 'confidence', 'evidenceIds', 'ranges', 'reason'],
    properties: {
      id: { type: 'string' }, enabled: { type: 'boolean' },
      confidence: { type: 'number', minimum: 0, maximum: 1 },
      evidenceIds: { type: 'array', minItems: 1, items: { type: 'string' } },
      ranges: { type: 'array', minItems: 1, items: range },
      reason: { type: 'string', minLength: 1, maxLength: 1000 },
      pacing: { type: 'string' },
    },
  }),
});



const audioSearchTool = (name, kind) => Object.freeze({
  name,
  description: `Search default ${kind === 'MUSIC' ? 'background-music' : 'sound-effect'} sources: the governed renderable registry, Freesound discovery when configured, and YouTube Audio Library assisted search. Call this before placement. Only results with renderable=true may be placed; authorization-required and assisted results require user action first.`,
  parametersJsonSchema: {
    type: 'object', additionalProperties: false,
    required: ['query', 'minimumDurationMs', 'maximumDurationMs', 'maximumResults'],
    properties: {
      query: { type: 'string', minLength: 2, maxLength: 160 },
      minimumDurationMs: { type: 'integer', minimum: 0, maximum: 3600000 },
      maximumDurationMs: { type: 'integer', minimum: 1, maximum: 3600000 },
      maximumResults: { type: 'integer', minimum: 1, maximum: 8 },
    },
  },
  kind,
});
const SEARCH_SOUND_EFFECTS_TOOL = audioSearchTool('search_sound_effects', 'SOUND_EFFECT');
const SEARCH_BACKGROUND_MUSIC_TOOL = audioSearchTool('search_background_music', 'MUSIC');

const operationTool = (name, description, required, properties) => Object.freeze({
  name,
  description: `${description} This tool constructs a draft operation only; it never edits the project.`,
  parametersJsonSchema: proposalEnvelope({
    type: 'object', additionalProperties: false,
    required: ['id', 'enabled', 'confidence', 'evidenceIds', ...required],
    properties: {
      id: { type: 'string' }, enabled: { type: 'boolean' },
      confidence: { type: 'number', minimum: 0, maximum: 1 },
      evidenceIds: { type: 'array', minItems: 1, items: { type: 'string' } },
      ...properties,
    },
  }),
});

const PROPOSE_AUDIO_CLEANUP_TOOL = operationTool(
  'propose_reduce_background_noise',
  'Reduce audible background noise in one exact source interval. Do not choose the interval or strength automatically.',
  ['sourceId', 'startMs', 'endMs', 'strength', 'reason'], {
  ...targetRangeProperties, strength: { type: 'number', minimum: 0, maximum: 1 },
},
);
const PROPOSE_DIALOGUE_LOUDNESS_TOOL = operationTool(
  'propose_set_dialogue_loudness',
  'Set the dialogue loudness target for one exact source interval.',
  ['sourceId', 'startMs', 'endMs', 'targetLufs', 'reason'], {
  ...targetRangeProperties, targetLufs: { type: 'number', minimum: -24, maximum: -12 },
},
);
const PROPOSE_MUSIC_DUCKING_TOOL = operationTool(
  'propose_set_music_ducking',
  'Duck background music by an exact amount during one exact project interval.',
  ['sourceId', 'startMs', 'endMs', 'duckDb', 'attackMs', 'releaseMs', 'reason'], {
  ...targetRangeProperties,
  duckDb: { type: 'number', minimum: -18, maximum: -3 },
  attackMs: { type: 'integer', minimum: 0, maximum: 2000 },
  releaseMs: { type: 'integer', minimum: 0, maximum: 5000 },
},
);
const PROPOSE_SOUND_EFFECT_TOOL = operationTool(
  'propose_place_sound_effect',
  'Propose a catalog sound effect only for explicit user intent and a grounded placement.',
  ['sourceId', 'startMs', 'endMs', 'catalogId', 'gainDb', 'reason'], {
  ...targetRangeProperties, catalogId: { type: 'string' },
  gainDb: { type: 'number', minimum: -36, maximum: 0 },
},
);
const PROPOSE_BACKGROUND_MUSIC_TOOL = operationTool(
  'propose_place_background_music',
  'Propose catalog background music only when requested, with a conservative speech-safe mix.',
  ['sourceId', 'startMs', 'endMs', 'catalogId', 'gainDb', 'loop', 'fadeInMs', 'fadeOutMs', 'reason'], {
  ...targetRangeProperties,
  catalogId: { type: 'string' }, gainDb: { type: 'number', minimum: -40, maximum: -6 },
  loop: { type: 'boolean' }, fadeInMs: { type: 'integer', minimum: 0, maximum: 10000 },
  fadeOutMs: { type: 'integer', minimum: 0, maximum: 10000 },
},
);
const PROPOSE_CAPTIONS_TOOL = operationTool(
  'propose_add_caption_cue',
  'Add one caption cue whose text is verbatim from the supplied timestamped transcript.',
  ['sourceId', 'startMs', 'endMs', 'text', 'style', 'reason'], {
  ...targetRangeProperties, text: { type: 'string', minLength: 1, maxLength: 120 },
  style: { type: 'string', enum: ['bold-bottom'] },
},
);
const colorAdjustmentTool = (parameter, minimum, maximum) => operationTool(
  `propose_adjust_${parameter.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`)}`,
  `Adjust only ${parameter} in one exact source interval, based on cited visual evidence.`,
  ['sourceId', 'startMs', 'endMs', 'value', 'reason'], {
  ...targetRangeProperties, value: { type: 'number', minimum, maximum },
},
);
const PROPOSE_COLOR_TOOLS = [
  colorAdjustmentTool('exposure', -1, 1), colorAdjustmentTool('temperature', -1, 1),
  colorAdjustmentTool('contrast', .75, 1.35), colorAdjustmentTool('saturation', .7, 1.3),
  colorAdjustmentTool('highlights', -1, 1), colorAdjustmentTool('shadows', -1, 1),
  colorAdjustmentTool('skinProtection', 0, 1),
];
const semanticVisualTool = (name, description) => operationTool(
  name, description,
  ['intent', 'range', 'subject', 'reason'], {
  intent: { type: 'string', enum: ['INTRODUCE', 'POINT_OUT', 'TRANSITION', 'REVEAL', 'EMPHASIZE', 'RECAP'] },
  range: {
    type: 'object', additionalProperties: false, required: ['sourceId', 'startMs', 'endMs'], properties: {
      sourceId: { type: 'string' }, startMs: { type: 'integer', minimum: 0 }, endMs: { type: 'integer', minimum: 1 },
    }
  },
  subject: { type: 'object' }, framing: { type: 'object' }, motion: { type: 'object' },
  reason: { type: 'string', minLength: 1, maxLength: 500 },
},
);
const PROPOSE_SEMANTIC_VISUAL_TOOLS = [
  semanticVisualTool('propose_add_visual_callout', 'Add one grounded callout around an observed subject.'),
  semanticVisualTool('propose_add_visual_spotlight', 'Add one grounded spotlight over an observed subject.'),
  semanticVisualTool('propose_reframe_subject', 'Reframe one grounded subject while preserving subjects and text.'),
];

const ASK_CLARIFICATION_TOOL = Object.freeze({
  name: 'ask_clarification',
  description: 'Pause and ask one to three consequential questions. Prefer concrete options and recommend the safest useful default when possible.',
  parametersJsonSchema: {
    type: 'object', additionalProperties: false, required: ['questions'], properties: {
      questions: {
        type: 'array', minItems: 1, maxItems: 3, items: {
          type: 'object', additionalProperties: false, required: ['id', 'prompt', 'options', 'recommendedOptionId'],
          properties: {
            id: { type: 'string' }, prompt: { type: 'string', maxLength: 500 },
            options: {
              type: 'array', minItems: 2, maxItems: 4, items: {
                type: 'object', additionalProperties: false, required: ['id', 'label', 'description'],
                properties: { id: { type: 'string' }, label: { type: 'string' }, description: { type: 'string' } },
              }
            }, recommendedOptionId: { type: 'string', description: 'Recommended option ID, or an empty string when no recommendation is justified.' },
          },
        }
      },
    },
  },
});
const FINALIZE_EDIT_PROGRAM_TOOL = Object.freeze({
  name: 'finalize_edit_program',
  description: 'Finish with the complete set of already validated draft operation IDs. The backend constructs and validates the atomic proposal.',
  parametersJsonSchema: {
    type: 'object', additionalProperties: false, required: ['expectedRevision', 'summary', 'operationIds'], properties: {
      expectedRevision: { type: 'integer', minimum: 0 }, summary: { type: 'string', maxLength: 2000 },
      operationIds: { type: 'array', items: { type: 'string' } },
    }
  },
});
const REMOVE_DRAFT_OPERATION_TOOL = Object.freeze({
  name: 'remove_draft_operation',
  description: 'Remove an operation from the private draft by its exact operation ID when revising the plan.',
  parametersJsonSchema: {
    type: 'object', additionalProperties: false, required: ['operationId'], properties: {
      operationId: { type: 'string' },
    }
  },
});
const FINISH_WITHOUT_PROPOSAL_TOOL = Object.freeze({
  name: 'finish_without_proposal', description: 'Finish safely when the request is unsupported or cannot be grounded.',
  parametersJsonSchema: { type: 'object', additionalProperties: false, required: ['reason'], properties: { reason: { type: 'string', maxLength: 1000 } } },
});

const AGENT_TOOLS = Object.freeze([
  INSPECT_VIDEO_RANGE_TOOL,
  SEARCH_SOUND_EFFECTS_TOOL, SEARCH_BACKGROUND_MUSIC_TOOL,
  PROPOSE_REMOVE_RANGES_TOOL,
  PROPOSE_AUDIO_CLEANUP_TOOL, PROPOSE_DIALOGUE_LOUDNESS_TOOL, PROPOSE_MUSIC_DUCKING_TOOL,
  PROPOSE_SOUND_EFFECT_TOOL, PROPOSE_BACKGROUND_MUSIC_TOOL, PROPOSE_CAPTIONS_TOOL, ...PROPOSE_COLOR_TOOLS,
  ...PROPOSE_SEMANTIC_VISUAL_TOOLS, ASK_CLARIFICATION_TOOL, FINALIZE_EDIT_PROGRAM_TOOL,
  REMOVE_DRAFT_OPERATION_TOOL, FINISH_WITHOUT_PROPOSAL_TOOL,
]);

module.exports = {
  AGENT_DECISION_SCHEMA, AGENT_TOOLS, INSPECT_VIDEO_RANGE_TOOL,
  PROPOSE_REMOVE_RANGES_TOOL,
};
