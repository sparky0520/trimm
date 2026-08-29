'use strict';



const targetRangeProperties = {
  sourceId: { type: 'string' },
  startMs: { type: 'integer' },
  endMs: { type: 'integer' },
  reason: { type: 'string' },
};

const PROPOSE_REMOVE_RANGES_TOOL = Object.freeze({
  name: 'propose_remove_ranges',
  description: `Propose removal of specific time ranges (such as speech fumbles, awkward silences, retakes, or unneeded content). Determine ranges based on the provided transcript timings.`,
  parameters: {
    type: 'object', additionalProperties: false,
    required: ['id', 'enabled', 'confidence', 'ranges', 'reason'],
    properties: {
      id: { type: 'string' }, enabled: { type: 'boolean' },
      confidence: { type: 'number' },
      ranges: {
        type: 'array',
        items: {
          type: 'object', additionalProperties: false,
          required: ['startMs', 'endMs'],
          properties: {
            startMs: { type: 'integer' },
            endMs: { type: 'integer' },
          }
        }
      },
      reason: { type: 'string' },
      pacing: { type: 'string' },
    },
  },
});

const operationTool = (name, description, required, properties) => Object.freeze({
  name,
  description,
  parameters: {
    type: 'object', additionalProperties: false,
    required: ['id', 'enabled', 'confidence', ...required],
    properties: {
      id: { type: 'string' }, enabled: { type: 'boolean' },
      confidence: { type: 'number' },
      ...properties,
    },
  },
});


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
  reason: { type: 'string' },
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

const PROPOSE_ADD_SECTION_TRANSITION_TOOL = operationTool(
  'propose_add_section_transition',
  'Add a video transition (e.g. fade-in, fade-out, crossfade, dissolve, wipe, slide, zoom, dip-to-black). Describe the desired effect in the description for custom transitions.',
  ['sourceId', 'startMs', 'endMs', 'preset', 'description', 'reason'], {
  ...targetRangeProperties,
  preset: { type: 'string' },
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

const PROPOSE_ADD_BGM_TOOL = operationTool(
  'propose_add_bgm',
  'Generate and add background music to the entire video or a specific interval based on a descriptive prompt (e.g. "cyberpunk synthwave").',
  ['prompt', 'reason'], {
  ...targetRangeProperties,
  prompt: { type: 'string' },
  timingCues: {
    type: 'array',
    description: 'Optional semantic cues for the audio generator (e.g. crescendos, drops). Find the exact sourceTimeMs from the transcript or video.',
    items: {
      type: 'object',
      properties: {
        sourceTimeMs: { type: 'number', description: 'Exact timestamp in the source video (e.g. from transcript) where this cue occurs.' },
        description: { type: 'string', description: 'What should happen in the audio at this time (e.g. "crescendo", "beat drop", "fade out").' }
      },
      required: ['sourceTimeMs', 'description']
    }
  },
  reason: { type: 'string' },
});

const PROPOSE_ADD_SFX_TOOL = operationTool(
  'propose_add_sfx',
  'Generate and add a sound effect to a specific interval based on a descriptive prompt (e.g. "cinematic whoosh").',
  ['sourceId', 'startMs', 'endMs', 'prompt', 'reason'], {
  ...targetRangeProperties,
  prompt: { type: 'string' },
  timingCues: {
    type: 'array',
    description: 'Optional semantic cues for the audio generator (e.g. crescendo, beat drop). Find the exact sourceTimeMs from the transcript or video.',
    items: {
      type: 'object',
      properties: {
        sourceTimeMs: { type: 'number', description: 'Exact timestamp in the source video (e.g. from transcript) where this cue occurs.' },
        description: { type: 'string', description: 'What should happen in the audio at this time.' }
      },
      required: ['sourceTimeMs', 'description']
    }
  },
});


const ASK_CLARIFICATION_TOOL = Object.freeze({
  name: 'ask_clarification',
  description: 'Pause and ask one to three consequential questions if the user request is vague, needs additional direction, or involves an unresolved consequential choice. Prefer concrete options and recommend the safest useful default when possible.',
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
  PROPOSE_REMOVE_RANGES_TOOL,
  PROPOSE_DIALOGUE_LOUDNESS_TOOL, PROPOSE_MUSIC_DUCKING_TOOL,
  PROPOSE_CAPTIONS_TOOL, PROPOSE_ASPECT_RATIO_TOOL, PROPOSE_TEXT_OVERLAY_TOOL, PROPOSE_ADD_SECTION_TRANSITION_TOOL, ...PROPOSE_COLOR_TOOLS,
  PROPOSE_ADD_BGM_TOOL, PROPOSE_ADD_SFX_TOOL,
  ASK_CLARIFICATION_TOOL, FINALIZE_EDIT_PROGRAM_TOOL,
  REMOVE_DRAFT_OPERATION_TOOL, FINISH_WITHOUT_PROPOSAL_TOOL,
]);

module.exports = {
  AGENT_TOOLS,
  PROPOSE_REMOVE_RANGES_TOOL,
};
