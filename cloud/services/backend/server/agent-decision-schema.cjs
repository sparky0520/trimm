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



const PROPOSE_ADD_BGM_TOOL = operationTool(
  'propose_add_bgm',
  'Generate and add a continuous background music bed. This tool is strictly for long-duration background music that spans the video. Do not use this for short musical effects.',
  ['prompt', 'reason'], {
  ...targetRangeProperties,
  prompt: { type: 'string' },
});

const PROPOSE_ADD_BGM_EFFECT_TOOL = operationTool(
  'propose_add_bgm_effect',
  'Generate and add a longer duration musical effect (default length around 10 to 12 seconds). Use this for musical stingers, crescendos, or drum rolls meant to be layered at specific times. Examples: "10 second rising crescendo", "long drum roll", "suspenseful orchestral build".',
  ['sourceId', 'startMs', 'endMs', 'prompt', 'reason'], {
  ...targetRangeProperties,
  prompt: { type: 'string' },
});

const PROPOSE_ADD_SFX_TOOL = operationTool(
  'propose_add_sfx',
  'Generate and add a short duration foley or sound effect (default length around 2 seconds). Do NOT use this for longer musical effects; use propose_add_bgm_effect for those. Examples: "short swoosh", "punch", "door slam", "quick cymbal crash".',
  ['sourceId', 'startMs', 'endMs', 'prompt', 'reason'], {
  ...targetRangeProperties,
  prompt: { type: 'string' },
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

const PROPOSE_SET_ROTATION_TOOL = Object.freeze({
  name: 'propose_set_rotation',
  description: 'Rotate a video clip by a specific angle (e.g., -90 for 90 degrees counter-clockwise, 90 for 90 degrees clockwise).',
  parameters: {
    type: 'object', additionalProperties: false, required: ['itemId', 'rotation', 'reason'], properties: {
      itemId: { type: 'string', description: 'The sourceId or identifier of the clip to rotate' },
      rotation: { type: 'number' },
      reason: { type: 'string' }
    }
  }
});

const PROPOSE_SET_SPEED_TOOL = Object.freeze({
  name: 'propose_set_speed',
  description: 'Change the playback speed of a clip. (e.g., 2.0 for 2x fast, 0.5 for half speed).',
  parameters: {
    type: 'object', additionalProperties: false, required: ['itemId', 'speed', 'reason'], properties: {
      itemId: { type: 'string' }, speed: { type: 'number' }, reason: { type: 'string' }
    }
  }
});

const PROPOSE_SET_VOLUME_TOOL = Object.freeze({
  name: 'propose_set_volume',
  description: 'Set the absolute volume for a clip (e.g. 0.0 to mute, 1.0 for normal, 2.0 for double volume).',
  parameters: {
    type: 'object', additionalProperties: false, required: ['itemId', 'volume', 'reason'], properties: {
      itemId: { type: 'string' }, volume: { type: 'number' }, reason: { type: 'string' }
    }
  }
});

const PROPOSE_CLEAR_ROTATION_TOOL = Object.freeze({
  name: 'propose_clear_rotation',
  description: 'Reset a clip\'s rotation back to 0.',
  parameters: {
    type: 'object', additionalProperties: false, required: ['itemId', 'reason'], properties: {
      itemId: { type: 'string' }, reason: { type: 'string' }
    }
  }
});








const AGENT_TOOLS = Object.freeze([
  PROPOSE_REMOVE_RANGES_TOOL,
  PROPOSE_DIALOGUE_LOUDNESS_TOOL, PROPOSE_MUSIC_DUCKING_TOOL,
  PROPOSE_CAPTIONS_TOOL, PROPOSE_ASPECT_RATIO_TOOL, PROPOSE_TEXT_OVERLAY_TOOL, PROPOSE_ADD_SECTION_TRANSITION_TOOL,
  PROPOSE_ADD_BGM_TOOL, PROPOSE_ADD_BGM_EFFECT_TOOL, PROPOSE_ADD_SFX_TOOL,
  PROPOSE_SET_ROTATION_TOOL,
  PROPOSE_SET_SPEED_TOOL, PROPOSE_SET_VOLUME_TOOL,
  PROPOSE_CLEAR_ROTATION_TOOL,
  ASK_CLARIFICATION_TOOL, FINALIZE_EDIT_PROGRAM_TOOL,
  REMOVE_DRAFT_OPERATION_TOOL, FINISH_WITHOUT_PROPOSAL_TOOL,
]);

module.exports = {
  AGENT_TOOLS,
  PROPOSE_REMOVE_RANGES_TOOL,
};
