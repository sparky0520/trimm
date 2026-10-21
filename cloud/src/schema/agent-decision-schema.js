


const targetRangeProperties = {
  sourceId: { type: 'string' },
  startMs: { type: 'integer' },
  endMs: { type: 'integer' },
  reason: { type: 'string' },
};

const APPLY_SCHEMA_PATCH_TOOL = Object.freeze({
  name: 'apply_schema_patch',
  description: 'Apply a patch to the video project by providing the updated CompositionSchema or modified timeline nodes. To save tokens and preserve precision, you only need to return the nodes or properties you are modifying or adding; unchanged nodes and fields (such as video src or unchanged clips) are automatically preserved from the previous schema. Every edit, cut, trim, caption styling, or timeline change MUST be executed via this tool. For script analysis, suggestions, or critique without timeline changes, use provide_script_advice.',
  parameters: {
    type: 'object',
    additionalProperties: false,
    required: ['reason', 'schema'],
    properties: {
      reason: { type: 'string', description: 'Explanation of the edits you made.' },
      removedNodeIds: {
        type: 'array',
        items: { type: 'string' },
        description: 'Optional list of node IDs to explicitly remove/delete from the timeline (e.g. when removing background audio, an effect filter, or a custom graphic).'
      },
      schema: {
        type: 'object',
        additionalProperties: false,
        required: ['settings', 'timeline'],
        properties: {
          settings: {
            type: 'object', additionalProperties: false, required: ['aspectRatio', 'backgroundColor', 'autoEnhance'],
            properties: { aspectRatio: { type: 'string' }, backgroundColor: { type: 'string' }, autoEnhance: { type: 'boolean' } }
          },
          metadata: {
            type: 'object', additionalProperties: true, description: 'Do not include or modify the metadata object. It is immutable and any changes will be ignored by the backend.'
          },
          timeline: {
            type: 'array',
            description: 'The visual timeline of the video. Nodes are rendered bottom-to-top (video -> effect -> text). To save tokens, you can return only the modified nodes (e.g., only styled text nodes), and unchanged nodes will be retained automatically.',
            items: {
              type: 'object',
              required: ['type', 'id', 'startMs', 'endMs'],
              properties: {
                type: { type: 'string', enum: ['video', 'text', 'effect', 'audio'] },
                id: { type: 'string' },
                startMs: { type: 'integer', description: 'The absolute start time in milliseconds on the final exported video timeline. INCREASING this moves the element later in the video; DECREASING this moves it earlier.' },
                endMs: { type: 'integer', description: 'The absolute end time in milliseconds on the final exported video timeline. INCREASING this makes the element stay on screen longer (extends end); DECREASING this cuts it off sooner (shortens duration).' },
                src: { type: 'string', description: 'Source media URI for video/audio. When modifying existing nodes, src is automatically preserved if omitted.' },
                sourceStartMs: { type: 'integer', description: '(Video/Audio only) The start time in milliseconds relative to the original raw source media file. INCREASING this trims/skips more of the beginning of the raw clip (starts playing from later in the source); DECREASING this reveals earlier footage from the raw source.' },
                transition: { type: 'string', description: 'Transition effect (e.g. "crossfade", "dip_to_black"). CRITICAL: MUST BE NULL or omitted unless the user explicitly requested transitions using words like "transition" or "crossfade". Hard cuts are standard.' },
                text: { type: 'string' },
                visible: { type: 'boolean', description: 'Controls on-screen visibility of text/caption nodes. true to display, false to hide. Transcribed caption cues default to visible: false on import until user explicitly asks to add/show captions.' },
                style: {
                  type: 'object',
                  properties: {
                    color: { type: 'string' },
                    fontSize: { type: 'number', description: 'Base font size in points (e.g. 50-65 for captions, 80-120 for titles). INCREASING this makes text larger and bolder; DECREASING this makes text smaller.' },
                    posY: { type: 'number', description: 'Normalized vertical position from -1.0 (bottom) to 1.0 (top). 0.0 is center. -0.35 is lower-third (standard caption position). INCREASING moves text UP towards top of frame (e.g. 0.0 center, 0.35 top third). DECREASING moves text DOWN towards bottom of frame (e.g. -0.5 lower).' },
                    posX: { type: 'number', description: 'Normalized horizontal position from -1.0 (left) to 1.0 (right). 0.0 is center. INCREASING moves text RIGHT (towards right edge); DECREASING moves text LEFT (towards left edge).' },
                    scale: { type: 'number', description: 'Scaling multiplier for text elements. 1.0 is standard normal size. INCREASING (e.g. 1.5) enlarges/zooms in; DECREASING (e.g. 0.8) shrinks/zooms out.' },
                    strokeColor: { type: 'string' },
                    strokeWidth: { type: 'number', description: 'Outline border stroke thickness in points. 0 is no outline; 2-4 is standard outline; 6-8 is heavy outline. INCREASING makes outline thicker; DECREASING makes outline thinner.' }
                  }
                },
                segments: {
                  type: 'array',
                  items: {
                    type: 'object',
                    required: ['text'],
                    properties: {
                      text: { type: 'string' },
                      color: { type: 'string' }
                    }
                  }
                },
                effectName: { type: 'string' },
                intensity: { type: 'number', description: '(Effect only) Effect strength from 0.0 (off/none) to 1.0 (maximum). INCREASING makes effect stronger/more prominent (e.g. heavier blur, more extreme pixelation); DECREASING makes effect subtler (e.g. gentle tint).' },
                autoDuck: { type: 'boolean' },
                volume: { type: 'number', description: '(Audio only) Volume multiplier. 1.0 is standard 100% volume; 0.0 is completely muted; 0.2-0.5 is background music level; >1.0 boosts volume. INCREASING makes audio louder; DECREASING makes audio quieter.' }
              },
              additionalProperties: false
            }
          }
        }
      }
    }
  }
});

const PROVIDE_SCRIPT_ADVICE_TOOL = Object.freeze({
  name: 'provide_script_advice',
  description: 'Provide script consultation, video critique, pacing analysis, structured script rewrites, or feedback when NO modifications are being made to the video timeline or captions. You have not modified the timeline or captions, so you must NEVER claim or imply that you edited the timeline or applied changes.',
  parameters: {
    type: 'object',
    additionalProperties: false,
    required: ['adviceMarkdown'],
    properties: {
      adviceMarkdown: {
        type: 'string',
        description: 'Comprehensive markdown response containing video/narrative analysis, script recommendations, and polished script.'
      }
    }
  }
});

const ASK_USER_QUESTION_TOOL = Object.freeze({
  name: 'ask_user_question',
  description: 'Pause and ask the user a question if the request is vague, needs additional direction, or involves an unresolved consequential choice. Use this only when absolutely necessary.',
  parameters: {
    type: 'object', additionalProperties: false, required: ['questions'], properties: {
      questions: {
        type: 'array', items: {
          type: 'object', additionalProperties: false, required: ['id', 'prompt', 'options', 'recommendedOptionId'],
          properties: {
            id: { type: 'string' },
            prompt: { type: 'string' },
            allowWriteIn: { type: 'boolean', description: 'If true, provides a text input for the user to write their own custom response if none of the options fit.' },
            options: {
              type: 'array', items: {
                type: 'object', additionalProperties: false, required: ['id', 'label', 'description'],
                properties: { id: { type: 'string' }, label: { type: 'string' }, description: { type: 'string' } },
              }
            },
            recommendedOptionId: { type: 'string' },
          },
        }
      },
    },
  },
});

const FINISH_WITHOUT_PROPOSAL_TOOL = Object.freeze({
  name: 'finish_without_proposal', description: 'Use this ONLY AS A LAST RESORT when the user request is fundamentally impossible or completely unsupported.',
  parameters: { type: 'object', additionalProperties: false, required: ['reason'], properties: { reason: { type: 'string' } } },
});

const SEARCH_AUDIO_CATALOGUE_TOOL = Object.freeze({
  name: 'search_audio_catalogue',
  description: 'Search the built-in library of 531 CC0 music tracks and sound effects by mood, category, duration, tags, or descriptive keywords. Use this to find the best background music beds or sound effects to place on the video timeline.',
  parameters: {
    type: 'object',
    required: ['type'],
    additionalProperties: false,
    properties: {
      type: { type: 'string', enum: ['music', 'sfx'], description: '"music" for background music beds, or "sfx" for sound effects.' },
      mood: {
        type: 'string',
        enum: [
          'ambient_calm', 'chill_lofi', 'cinematic_epic', 'corporate_inspiring',
          'dark_moody', 'emotional_piano', 'energetic_electronic', 'festive_celebration',
          'funky_groove', 'happy_playful', 'indian_fusion', 'romantic_soft',
          'upbeat_pop', 'vlog_acoustic'
        ],
        description: 'For music: specific mood category.'
      },
      category: {
        type: 'string',
        enum: [
          'whoosh', 'swipe', 'riser', 'page_turn',
          'impact', 'bass_drop', 'explosion', 'stinger',
          'click', 'pop', 'ding', 'shutter', 'typing', 'tick', 'cash', 'success', 'buzzer',
          'laugh', 'gasp', 'crowd', 'cartoon', 'sci_fi', 'glitch', 'sparkle',
          'knock', 'heartbeat', 'splash', 'alarm'
        ],
        description: 'For sfx: specific sound effect category.'
      },
      query: { type: 'string', description: 'Search keywords or description (e.g. "warm guitar", "camera shutter", "punchy bass").' },
      maxDurationS: { type: 'number', description: 'Optional maximum duration in seconds.' },
      limit: { type: 'integer', description: 'Maximum number of items to return (1-10, default 5).' }
    }
  }
});

const getAgentTools = () => {
  return Object.freeze([
    APPLY_SCHEMA_PATCH_TOOL,
    PROVIDE_SCRIPT_ADVICE_TOOL,
    SEARCH_AUDIO_CATALOGUE_TOOL,
    ASK_USER_QUESTION_TOOL, 
    FINISH_WITHOUT_PROPOSAL_TOOL,
  ]);
};

export { 
  getAgentTools,
  APPLY_SCHEMA_PATCH_TOOL,
  PROVIDE_SCRIPT_ADVICE_TOOL,
  SEARCH_AUDIO_CATALOGUE_TOOL,
  ASK_USER_QUESTION_TOOL,
  FINISH_WITHOUT_PROPOSAL_TOOL,
};
