


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
                startMs: { type: 'integer' },
                endMs: { type: 'integer' },
                src: { type: 'string' },
                sourceStartMs: { type: 'integer' },
                transition: { type: 'string' },
                text: { type: 'string' },
                style: {
                  type: 'object',
                  properties: {
                    color: { type: 'string' },
                    fontSize: { type: 'number' },
                    posY: { type: 'number' },
                    posX: { type: 'number' },
                    scale: { type: 'number' },
                    strokeColor: { type: 'string' },
                    strokeWidth: { type: 'number' }
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
                intensity: { type: 'number' },
                autoDuck: { type: 'boolean' },
                volume: { type: 'number' }
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
