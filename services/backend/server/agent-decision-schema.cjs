'use strict';

const observationSchema = {
  type: 'object', additionalProperties: false,
  required: ['id', 'sourceId', 'startMs', 'endMs', 'description', 'confidence', 'resolution'],
  properties: {
    id: { type: 'string' },
    sourceId: { type: 'string' },
    startMs: { type: 'integer' },
    endMs: { type: 'integer' },
    description: { type: 'string' },
    confidence: { type: 'number' },
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
        'remove_ranges', 'set_color_grade', 'set_audio_levels',
        'add_captions', 'add_vfx', 'add_text_overlay'
      ]
    },
    enabled: { type: 'boolean' },
    confidence: { type: 'number' },
    evidenceIds: { type: 'array', items: { type: 'string' } },
  },};

const observations = { type: 'array', items: observationSchema };

const AGENT_DECISION_SCHEMA = Object.freeze({
  type: 'object',
  additionalProperties: false,
  required: ['type', 'observations'],
  properties: {
    type: { type: 'string', enum: ['edit_program', 'clarification', 'fallback'] },
    observations,
    
    // edit_program fields
    expectedRevision: { type: 'integer' },
    summary: { type: 'string' },
    operations: { type: 'array', items: operationSchema },
    
    // clarification fields
    questions: {
      type: 'array', items: {
        type: 'object', additionalProperties: false, required: ['id', 'prompt', 'options', 'recommendedOptionId'],
        properties: {
          id: { type: 'string' }, prompt: { type: 'string' }, recommendedOptionId: { type: 'string' },
          options: {
            type: 'array', items: {
              type: 'object', additionalProperties: false, required: ['id', 'label', 'description'],
              properties: { id: { type: 'string' }, label: { type: 'string' }, description: { type: 'string' } },
            }
          },
        },
      }
    },
    
    // fallback fields
    reason: { type: 'string' },
  },
});

const INSPECT_VIDEO_RANGE_TOOL = Object.freeze({
  name: 'inspect_video_range',
  description: 'Upload and inspect a source video range. Use LOW normally; use HIGH only when an overlapping LOW inspection left a specific uncertainty.',
  parameters: {
    type: 'object', additionalProperties: false,
    required: ['sourceId', 'startMs', 'endMs', 'resolution', 'uncertainty'],
    properties: {
      sourceId: { type: 'string' }, startMs: { type: 'integer' },
      endMs: { type: 'integer' }, resolution: { type: 'string', enum: ['LOW', 'HIGH'] },
      uncertainty: { type: 'string' },
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
    expectedRevision: { type: 'integer' },
    summary: { type: 'string' },
    operation,
    observations: { type: 'array', items: proposedObservation },
  },
});

const range = {
  type: 'object', additionalProperties: false,
  required: ['sourceId', 'startMs', 'endMs', 'evidenceIds'],
  properties: {
    sourceId: { type: 'string' },
    startMs: { type: 'integer' },
    endMs: { type: 'integer' },
    evidenceIds: { type: 'array', items: { type: 'string' } },
  },
};

const targetRangeProperties = {
  sourceId: { type: 'string' },
  startMs: { type: 'integer' },
  endMs: { type: 'integer' },
  reason: { type: 'string' },
};

const PROPOSE_REMOVE_RANGES_TOOL = Object.freeze({
  name: 'propose_remove_ranges',
  description: `Propose removal of specific time ranges (such as speech fumbles, awkward silences, retakes, or unneeded content). Every range must be supported by supplied evidence artifacts. This tool constructs a proposal draft only; it never edits the project directly.`,
  parameters: proposalEnvelope({
    type: 'object', additionalProperties: false,
    required: ['id', 'enabled', 'confidence', 'evidenceIds', 'ranges', 'reason'],
    properties: {
      id: { type: 'string' }, enabled: { type: 'boolean' },
      confidence: { type: 'number' },
      evidenceIds: { type: 'array', items: { type: 'string' } },
      ranges: { type: 'array', items: range },
      reason: { type: 'string' },
      pacing: { type: 'string' },
    },
  }),
});





const operationTool = (name, description, required, properties) => Object.freeze({
  name,
  description: `${description} This tool constructs a draft operation only; it never edits the project.`,
  parameters: proposalEnvelope({
    type: 'object', additionalProperties: false,
    required: ['id', 'enabled', 'confidence', 'evidenceIds', ...required],
    properties: {
      id: { type: 'string' }, enabled: { type: 'boolean' },
      confidence: { type: 'number' },
      evidenceIds: { type: 'array', items: { type: 'string' } },
      ...properties,
    },
  }),
});


const PROPOSE_DIALOGUE_LOUDNESS_TOOL = operationTool(
  'propose_set_dialogue_loudness',
  'Set the dialogue loudness target for one exact source interval.',
  ['sourceId', 'startMs', 'endMs', 'targetLufs', 'reason'], {
  ...targetRangeProperties, targetLufs: { type: 'number' },
},
);
const PROPOSE_MUSIC_DUCKING_TOOL = operationTool(
  'propose_set_music_ducking',
  'Duck background music by an exact amount during one exact project interval.',
  ['sourceId', 'startMs', 'endMs', 'duckDb', 'attackMs', 'releaseMs', 'reason'], {
  ...targetRangeProperties,
  duckDb: { type: 'number' },
  attackMs: { type: 'integer' },
  releaseMs: { type: 'integer' },
},
);

const PROPOSE_CAPTIONS_TOOL = operationTool(
  'propose_add_captions',
  'Enable captions. You can optionally specify a source interval or a specific speakerId. The timing and text will be automatically extracted from the transcript.',
  ['style', 'reason'], {
  ...targetRangeProperties,
  speakerId: { type: 'string' },
  style: { type: 'string', enum: ['bold-bottom'] },
},
);

const PROPOSE_ASPECT_RATIO_TOOL = operationTool(
  'propose_set_aspect_ratio',
  'Change the aspect ratio of the video (e.g., to vertical, landscape, or square).',
  ['ratio', 'reason'], {
  ratio: { type: 'string', enum: ['PORTRAIT', 'LANDSCAPE', 'SQUARE'] },
},
);

const PROPOSE_TEXT_OVERLAY_TOOL = operationTool(
  'propose_add_text_overlay',
  'Generate a native text overlay with dynamic graphics (like a comic book "BANG", title cards, or floating text). Describe the desired effect and text in the description.',
  ['sourceId', 'startMs', 'endMs', 'text', 'description', 'reason'], {
  ...targetRangeProperties,
  text: { type: 'string' },
  description: { type: 'string' },
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


const ASK_CLARIFICATION_TOOL = Object.freeze({
  name: 'ask_clarification',
  description: 'Pause and ask one to three consequential questions. Prefer concrete options and recommend the safest useful default when possible.',
  parameters: {
    type: 'object', additionalProperties: false, required: ['questions'], properties: {
      questions: {
        type: 'array', items: {
          type: 'object', additionalProperties: false, required: ['id', 'prompt', 'options', 'recommendedOptionId'],
          properties: {
            id: { type: 'string' }, prompt: { type: 'string' },
            options: {
              type: 'array', items: {
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
  parameters: {
    type: 'object', additionalProperties: false, required: ['expectedRevision', 'summary', 'operationIds'], properties: {
      expectedRevision: { type: 'integer' }, summary: { type: 'string' },
      operationIds: { type: 'array', items: { type: 'string' } },
    }
  },
});
const REMOVE_DRAFT_OPERATION_TOOL = Object.freeze({
  name: 'remove_draft_operation',
  description: 'Remove an operation from the private draft by its exact operation ID when revising the plan.',
  parameters: {
    type: 'object', additionalProperties: false, required: ['operationId'], properties: {
      operationId: { type: 'string' },
    }
  },
});
const FINISH_WITHOUT_PROPOSAL_TOOL = Object.freeze({
  name: 'finish_without_proposal', description: 'Finish safely when the request is unsupported or cannot be grounded.',
  parameters: { type: 'object', additionalProperties: false, required: ['reason'], properties: { reason: { type: 'string' } } },
});

const AGENT_TOOLS = Object.freeze([
  INSPECT_VIDEO_RANGE_TOOL,
  PROPOSE_REMOVE_RANGES_TOOL,
  PROPOSE_DIALOGUE_LOUDNESS_TOOL, PROPOSE_MUSIC_DUCKING_TOOL,
  PROPOSE_CAPTIONS_TOOL, PROPOSE_ASPECT_RATIO_TOOL, PROPOSE_TEXT_OVERLAY_TOOL, ...PROPOSE_COLOR_TOOLS,
  ASK_CLARIFICATION_TOOL, FINALIZE_EDIT_PROGRAM_TOOL,
  REMOVE_DRAFT_OPERATION_TOOL, FINISH_WITHOUT_PROPOSAL_TOOL,
]);

module.exports = {
  AGENT_DECISION_SCHEMA, AGENT_TOOLS, INSPECT_VIDEO_RANGE_TOOL,
  PROPOSE_REMOVE_RANGES_TOOL,
};
