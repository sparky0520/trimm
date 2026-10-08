


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

const PROPOSE_TEXT_OVERLAY_TOOL_ANDROID = operationTool(
  'propose_add_text_overlay',
  'Generate a native text overlay with dynamic graphics. \nSUPPORTED STYLES ONLY:\n- Fonts: "plain", "comic", "neon", "typewriter", "elegant"\n- Text Style: color, strokeColor, strokeWidth, shadowRadius, shadowColor, fontSize\n- Layout: posX, posY, scale, rotation\n- Background Shapes: rectangle, circle, starburst (with color, stroke, dimensions)\nIMPORTANT: If the user requests a style, font, or animation NOT explicitly listed above (e.g. gradients, italics, 3D text, animations), do NOT use this tool. Instead, use finish_without_proposal or respond explaining that the requested style is not supported.',
  ['sourceId', 'startMs', 'endMs', 'text', 'description', 'styling', 'reason'], {
  ...targetRangeProperties,
  text: { type: 'string' },
  description: { type: 'string', description: 'Describe the desired effect and graphics (e.g., comic book "BANG").' },
  styling: { type: 'string', description: 'Detailed visual styling instructions restricted to supported styles (font size, colors, positioning, drop shadows).' }
},
);

const PROPOSE_TEXT_OVERLAY_TOOL_IOS = operationTool(
  'propose_add_text_overlay',
  'Generate a native text overlay with dynamic graphics for iOS. \nSUPPORTED STYLES ONLY:\n- Fonts: "plain", "comic", "neon", "typewriter", "elegant", "system-bold", "system-heavy"\n- Text Style: color, strokeColor, strokeWidth, shadowRadius, shadowColor, fontSize, CoreAnimation (glow, blur radius)\n- Layout: posX, posY, scale, rotation, zIndex\n- Animations (iOS exclusive): "fade", "scaleUp", "typewriter", "slideIn"\n- Background Shapes: rectangle, circle, starburst (with color, stroke, dimensions, cornerRadius)\nIMPORTANT: If the user requests a style NOT explicitly listed above, do NOT use this tool. Instead, use finish_without_proposal or respond explaining that the requested style is not supported.',
  ['sourceId', 'startMs', 'endMs', 'text', 'description', 'styling', 'reason'], {
  ...targetRangeProperties,
  text: { type: 'string' },
  description: { type: 'string', description: 'Describe the desired effect and graphics (e.g., comic book "BANG").' },
  styling: { type: 'string', description: 'Detailed visual styling instructions restricted to supported styles (font size, colors, positioning, drop shadows, animations).' }
},
);

const PROPOSE_ADD_SECTION_TRANSITION_TOOL_ANDROID = operationTool(
  'propose_add_section_transition',
  'Add a video transition (e.g. fade-in, fade-out, crossfade, dissolve, wipe, slide, zoom, dip-to-black). Describe the desired effect in the description for custom transitions.',
  ['sourceId', 'startMs', 'endMs', 'preset', 'description', 'reason'], {
  ...targetRangeProperties,
  preset: { type: 'string' },
  description: { type: 'string' },
},
);

const PROPOSE_ADD_SECTION_TRANSITION_TOOL_IOS = operationTool(
  'propose_add_section_transition',
  'Add a video transition (e.g. fade-in, fade-out, crossfade, dissolve, wipe, slide, zoom, dip-to-black, push, reveal, CoreImage custom filters like ripples or blur-fades). Describe the desired effect in the description for custom transitions.',
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
  description: `Generate a Universal Video JSON DSL document to edit the video. Use this to apply complex spatial animations, compositing, blend modes, audio fades, and mathematical effects (like colorMatrix). 
Recipes:
- Vintage Film / Warmth: Use a colorMatrix to boost reds and yellows. Reduce contrast slightly. "effects": [{"type": "colorMatrix", "matrix": [1.2, 0.1, 0, 0, 0,  0, 0.9, 0, 0, 0,  0, 0, 0.8, 0, 0,  0, 0, 0, 1, 0]}]
- VHS Glitch: Duplicate the target media layer 3 times perfectly overlapping. On layer 1, apply a colorMatrix isolating Red. On layer 2, isolate Green. On layer 3, isolate Blue. Set all three layers blendMode to screen. Apply rapid spatial keyframes (jittering x by +/- 5 pixels) to the Red and Blue layers.`,
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
                    id: { type: 'string' }, type: { type: 'string' },
                    source: { type: 'object', additionalProperties: false, required: ['url'], properties: { url: { type: 'string' }, mediaStartTimeMs: { type: 'integer' } } },
                    startMs: { type: 'integer' }, durationMs: { type: 'integer' },
                    spatial: {
                      type: 'object', additionalProperties: false, required: ['keyframes'], properties: {
                        anchor: { type: 'object', additionalProperties: false, required: ['x', 'y'], properties: { x: { type: 'number' }, y: { type: 'number' } } },
                        keyframes: { type: 'array', items: { type: 'object', additionalProperties: false, required: ['timeMs'], properties: { timeMs: { type: 'integer' }, x: { type: 'number' }, y: { type: 'number' }, scale: { type: 'number' }, rotation: { type: 'number' }, easing: { type: 'string' } } } }
                      }
                    },
                    compositing: {
                      type: 'object', additionalProperties: false, properties: {
                        blendMode: { type: 'string' },
                        opacity: { type: 'object', additionalProperties: false, required: ['keyframes'], properties: { keyframes: { type: 'array', items: { type: 'object', additionalProperties: false, required: ['timeMs', 'value'], properties: { timeMs: { type: 'integer' }, value: { type: 'number' }, easing: { type: 'string' } } } } } }
                      }
                    },
                    audio: {
                      type: 'object', additionalProperties: false, properties: {
                        volume: { type: 'object', additionalProperties: false, required: ['keyframes'], properties: { keyframes: { type: 'array', items: { type: 'object', additionalProperties: false, required: ['timeMs', 'value'], properties: { timeMs: { type: 'integer' }, value: { type: 'number' }, easing: { type: 'string' } } } } } }
                      }
                    },
                    effects: {
                      type: 'array', items: { type: 'object', additionalProperties: false, required: ['type'], properties: { type: { type: 'string' }, matrix: { type: 'array', items: { type: 'number' } } } }
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








const getAgentTools = (platform) => {
  const isIOS = platform === 'ios';
  return Object.freeze([
    PROPOSE_REMOVE_RANGES_TOOL,
    PROPOSE_DIALOGUE_LOUDNESS_TOOL, PROPOSE_MUSIC_DUCKING_TOOL,
    PROPOSE_CAPTIONS_TOOL, PROPOSE_ASPECT_RATIO_TOOL,
    isIOS ? PROPOSE_TEXT_OVERLAY_TOOL_IOS : PROPOSE_TEXT_OVERLAY_TOOL_ANDROID,
    isIOS ? PROPOSE_ADD_SECTION_TRANSITION_TOOL_IOS : PROPOSE_ADD_SECTION_TRANSITION_TOOL_ANDROID,
    PROPOSE_ADD_BGM_TOOL, PROPOSE_ADD_BGM_EFFECT_TOOL, PROPOSE_ADD_SFX_TOOL,
    PROPOSE_SET_ROTATION_TOOL,
    PROPOSE_SET_SPEED_TOOL, PROPOSE_SET_VOLUME_TOOL,
    PROPOSE_CLEAR_ROTATION_TOOL,
    PROPOSE_APPLY_VIDEO_DSL_TOOL,
    ASK_CLARIFICATION_TOOL, FINALIZE_EDIT_PROGRAM_TOOL,
    REMOVE_DRAFT_OPERATION_TOOL, FINISH_WITHOUT_PROPOSAL_TOOL,
  ]);
};

export { 
  getAgentTools,
  PROPOSE_REMOVE_RANGES_TOOL,
 };
