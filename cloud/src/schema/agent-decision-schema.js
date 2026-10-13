


const targetRangeProperties = {
  sourceId: { type: 'string' },
  startMs: { type: 'integer' },
  endMs: { type: 'integer' },
  reason: { type: 'string' },
};

const APPLY_SCHEMA_PATCH_TOOL = Object.freeze({
  name: 'apply_schema_patch',
  description: 'Apply a patch to the video project by directly providing the new CompositionSchema. The CompositionSchema has a timeline array containing video, text, and effect nodes. You should modify this array to implement cuts (by splitting/removing video nodes), captions (by modifying text nodes), and visual effects.',
  parameters: {
    type: 'object',
    additionalProperties: false,
    required: ['reason', 'schema'],
    properties: {
      reason: { type: 'string', description: 'Explanation of the edits you made.' },
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
            description: 'The visual timeline of the video. Nodes are rendered bottom-to-top (video -> effect -> text). All nodes MUST have type, id, startMs, endMs.',
            items: {
              type: 'object',
              required: ['type', 'id', 'startMs', 'endMs'],
              properties: {
                type: { type: 'string', enum: ['video', 'text', 'effect', 'audio'] },
                id: { type: 'string' },
                startMs: { type: 'integer', description: 'The absolute start time in milliseconds on the final exported video timeline. Increasing this moves the element later in the video.' },
                endMs: { type: 'integer', description: 'The absolute end time in milliseconds on the final exported video timeline. Increasing this makes the element stay on screen longer.' },
                src: { type: 'string' },
                sourceStartMs: { type: 'integer', description: '(Video/Audio only) The start time in milliseconds relative to the original raw source media file. Increasing this number trims/skips the beginning of the raw clip so it starts playing from a later point.' },
                transition: { type: 'string' },
                text: { type: 'string' },
                visible: { type: 'boolean', description: 'Controls on-screen visibility of text/caption nodes. true to display, false to hide. Transcribed caption cues default to visible: false on import until user explicitly asks to add/show captions.' },
                style: {
                  type: 'object',
                  properties: {
                    color: { type: 'string' },
                    fontSize: { type: 'number', description: 'The font size for text elements. Increasing this makes the text visually larger. Default is usually around 40 to 60.' },
                    posY: { type: 'number', description: 'The vertical position of the text. Increasing this number moves the text downwards. A value of 0 is typically centered.' },
                    posX: { type: 'number', description: 'The horizontal position of the text. Increasing this number moves the text to the right. A value of 0 is typically centered.' },
                    scale: { type: 'number', description: 'The scaling multiplier for text elements. 1.0 is normal size. Increasing this (e.g. to 2.0) doubles the size. Decreasing this (e.g. to 0.5) halves it.' },
                    strokeColor: { type: 'string' },
                    strokeWidth: { type: 'number', description: 'The thickness of the text outline/stroke. Increasing this makes the outline thicker and more pronounced. Typical values are 2 to 6.' }
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
                intensity: { type: 'number', description: '(Effect only) The strength of the applied visual effect. Range is typically 0.0 to 1.0. Increasing this makes the effect more severe (e.g., more blurry, more pixelated).' },
                autoDuck: { type: 'boolean' },
                volume: { type: 'number', description: '(Audio only) The volume multiplier for the audio track. 1.0 is original volume. Increasing this (e.g. to 2.0) doubles the volume, decreasing it (e.g. to 0.5) halves the volume. 0.0 is muted.' }
              },
              additionalProperties: false
            }
          }
        }
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

const getAgentTools = () => {
  return Object.freeze([
    APPLY_SCHEMA_PATCH_TOOL,
    ASK_USER_QUESTION_TOOL, 
    FINISH_WITHOUT_PROPOSAL_TOOL,
  ]);
};

export { 
  getAgentTools,
  APPLY_SCHEMA_PATCH_TOOL,
};
