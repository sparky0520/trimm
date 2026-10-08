


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
  'Enable captions. You MUST provide the segmented captions (cues) by intelligently grouping the transcript words into semantically intact sentences or phrases. Ensure you provide exact startMs and endMs matching the transcript timing.',
  ['style', 'reason', 'cues'], {
  ...targetRangeProperties,
  speakerId: { type: 'string' },
  style: { type: 'string', enum: ['bold-bottom'] },
  cues: {
    type: 'array',
    items: {
      type: 'object',
      required: ['text', 'range'],
      properties: {
        text: { type: 'string' },
        range: {
          type: 'object',
          required: ['startMs', 'endMs'],
          properties: {
            startMs: { type: 'integer' },
            endMs: { type: 'integer' }
          }
        }
      }
    }
  }
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

const PROPOSE_ADD_TEXT_OVERLAY_TOOL = operationTool(
  'propose_add_text_overlay',
  'Add a simple text overlay. Use this for basic titles, subtitles, or labels. For complex animations or custom rendering, use propose_apply_video_dsl instead.',
  ['sourceId', 'startMs', 'endMs', 'text', 'style', 'reason'], {
  ...targetRangeProperties,
  text: { type: 'string' },
  style: { type: 'string', enum: ['standard', 'comic', 'neon', 'elegant', 'typewriter'] },
});

const PROPOSE_ADD_SECTION_TRANSITION_TOOL = operationTool(
  'propose_add_section_transition',
  'Add a standard video transition between clips. For complex custom transitions, use propose_apply_video_dsl instead.',
  ['sourceId', 'startMs', 'endMs', 'type', 'reason'], {
  ...targetRangeProperties,
  type: { type: 'string', enum: ['fade', 'dissolve', 'wipe', 'slide', 'zoom'] },
});




const ASK_USER_QUESTION_TOOL = Object.freeze({
  name: 'ask_user_question',
  description: 'Pause and ask the user a question if the request is vague, needs additional direction, or involves an unresolved consequential choice. Use this only when absolutely necessary.',
  parameters: {
    type: 'object', additionalProperties: false, required: ['questions'], properties: {
      questions: {
        type: 'array', items: {
          type: 'object', additionalProperties: false, required: ['id', 'question', 'options', 'isMultiSelect'],
          properties: {
            id: { type: 'string' },
            question: { type: 'string' },
            isMultiSelect: { type: 'boolean', description: 'If true, allows the user to select multiple options.' },
            options: {
              type: 'array', items: {
                type: 'object', additionalProperties: false, required: ['id', 'label', 'description'],
                properties: { id: { type: 'string' }, label: { type: 'string' }, description: { type: 'string' } },
              }
            },
            recommendedOptionId: { type: 'string', description: 'Recommended option ID, or an empty string when no recommendation is justified.' },
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
  name: 'finish_without_proposal', description: 'Use this ONLY AS A LAST RESORT when the user request is fundamentally impossible or completely unsupported. Make every attempt to use other tools or apply video DSL before giving up.',
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

const PROPOSE_SET_SPEED_TOOL = operationTool(
  'propose_set_speed',
  'Change the playback speed of a clip or a specific segment. (e.g., 2.0 for 2x fast, 0.5 for half speed).',
  ['itemId', 'speed', 'reason'], {
  ...targetRangeProperties,
  itemId: { type: 'string' }, speed: { type: 'number' }, reason: { type: 'string' }
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

const PROPOSE_APPLY_VIDEO_DSL_TOOL = Object.freeze({
  name: 'propose_apply_video_dsl',
  description: `Generate a Universal Video JSON DSL document to edit the video. Use this as your "God Tool" to apply advanced motion graphics, complex spatial animations, custom styling, compositing, blend modes, and mathematical effects (like colorMatrix) that go beyond the basic predefined tools. 
Recipes:
- Vintage Film / Warmth: Use a colorMatrix to boost reds and yellows. Reduce contrast slightly. "effects": [{"type": "colorMatrix", "matrix": [1.2, 0.1, 0, 0, 0,  0, 0.9, 0, 0, 0,  0, 0, 0.8, 0, 0,  0, 0, 0, 1, 0]}]
- Black and White: Mix RGB channels equally to remove color. "effects": [{"type": "colorMatrix", "matrix": [0.33, 0.33, 0.33, 0, 0,  0.33, 0.33, 0.33, 0, 0,  0.33, 0.33, 0.33, 0, 0,  0, 0, 0, 1, 0]}]`,
  parameters: {
    type: 'object',
    additionalProperties: false,
    required: ['id', 'enabled', 'confidence', 'reason', 'dsl'],
    properties: {
      id: { type: 'string' },
      enabled: { type: 'boolean' },
      confidence: { type: 'number' },
      reason: { type: 'string' },
      dsl: {
        type: 'object',
        additionalProperties: false,
        required: ['project', 'timeline'],
        properties: {
          project: {
            type: 'object', additionalProperties: false, required: ['dimensions', 'fps', 'durationMs'],
            properties: {
              dimensions: { type: 'object', additionalProperties: false, required: ['width', 'height'], properties: { width: { type: 'number' }, height: { type: 'number' } } },
              fps: { type: 'number' }, durationMs: { type: 'integer' }
            }
          },
          timeline: {
            type: 'object', additionalProperties: false, required: ['layers'],
            properties: {
              layers: {
                type: 'array', items: {
                  type: 'object', additionalProperties: false, required: ['id', 'type', 'startMs', 'durationMs'],
                  properties: {
                    id: { type: 'string', description: 'Unique identifier for the layer (e.g. "vhs_red").' },
                    type: { type: 'string', enum: ['media', 'text', 'shape'], description: 'Type of layer. "media" is standard video.' },
                    source: { type: 'object', additionalProperties: false, required: ['url'], properties: { url: { type: 'string', description: 'Source URL or fingerprint of the media.' }, mediaStartTimeMs: { type: 'integer', description: 'Offset into the source media to start playing from.' } } },
                    startMs: { type: 'integer', description: 'Time on the global timeline when this layer appears.' },
                    durationMs: { type: 'integer', description: 'How long this layer stays on the timeline.' },
                    spatial: {
                      type: 'object', additionalProperties: false, required: ['keyframes'], description: 'Controls movement, scaling, and rotation over time.', properties: {
                        anchor: { type: 'object', additionalProperties: false, required: ['x', 'y'], properties: { x: { type: 'number', description: '0.0 to 1.0 (default 0.5 center)' }, y: { type: 'number', description: '0.0 to 1.0 (default 0.5 center)' } } },
                        keyframes: { type: 'array', description: 'List of spatial keyframes. timeMs is relative to the layer startMs.', items: { type: 'object', additionalProperties: false, required: ['timeMs'], properties: { timeMs: { type: 'integer' }, x: { type: 'number', description: 'X offset in pixels.' }, y: { type: 'number', description: 'Y offset in pixels.' }, scale: { type: 'number', description: 'Multiplier (1.0 is default).' }, rotation: { type: 'number', description: 'Rotation in degrees.' }, easing: { type: 'string', enum: ['linear', 'ease-in', 'ease-out', 'ease-in-out'] } } } }
                      }
                    },
                    compositing: {
                      type: 'object', additionalProperties: false, description: 'Controls blending and opacity.', properties: {
                        blendMode: { type: 'string', enum: ['normal', 'multiply', 'screen', 'overlay'], description: 'Mathematical blend mode against layers below it.' },
                        opacity: { type: 'object', additionalProperties: false, required: ['keyframes'], properties: { keyframes: { type: 'array', items: { type: 'object', additionalProperties: false, required: ['timeMs', 'value'], properties: { timeMs: { type: 'integer' }, value: { type: 'number', description: 'Opacity from 0.0 to 1.0.' }, easing: { type: 'string', enum: ['linear', 'ease-in', 'ease-out', 'ease-in-out'] } } } } } }
                      }
                    },
                    audio: {
                      type: 'object', additionalProperties: false, description: 'Controls audio volume over time.', properties: {
                        volume: { type: 'object', additionalProperties: false, required: ['keyframes'], properties: { keyframes: { type: 'array', items: { type: 'object', additionalProperties: false, required: ['timeMs', 'value'], properties: { timeMs: { type: 'integer' }, value: { type: 'number', description: 'Volume multiplier (1.0 is normal, 0.0 is mute).' }, easing: { type: 'string', enum: ['linear', 'ease-in', 'ease-out', 'ease-in-out'] } } } } } }
                      }
                    },
                    effects: {
                      type: 'array', description: 'Mathematical filters applied to the layer.', items: { type: 'object', additionalProperties: false, required: ['type', 'matrix'], properties: { type: { type: 'string', enum: ['colorMatrix'] }, matrix: { type: 'array', description: 'MUST be exactly 20 numbers representing the 4x5 color matrix.', items: { type: 'number' }, minItems: 20, maxItems: 20 } } }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
});








const getAgentTools = () => {
  return Object.freeze([
    PROPOSE_REMOVE_RANGES_TOOL,
    PROPOSE_DIALOGUE_LOUDNESS_TOOL, PROPOSE_MUSIC_DUCKING_TOOL,
    PROPOSE_CAPTIONS_TOOL, PROPOSE_ASPECT_RATIO_TOOL,
    PROPOSE_ADD_TEXT_OVERLAY_TOOL, PROPOSE_ADD_SECTION_TRANSITION_TOOL,
    PROPOSE_SET_ROTATION_TOOL,
    PROPOSE_SET_SPEED_TOOL, PROPOSE_SET_VOLUME_TOOL,
    PROPOSE_CLEAR_ROTATION_TOOL,
    PROPOSE_APPLY_VIDEO_DSL_TOOL,
    ASK_USER_QUESTION_TOOL, FINALIZE_EDIT_PROGRAM_TOOL,
    REMOVE_DRAFT_OPERATION_TOOL, FINISH_WITHOUT_PROPOSAL_TOOL,
  ]);
};

export { 
  getAgentTools,
  PROPOSE_REMOVE_RANGES_TOOL,
 };
