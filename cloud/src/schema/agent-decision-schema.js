// Definitions matching app/src/types/contracts.ts

const TransformSchema = {
  type: 'object',
  properties: {
    x: { type: 'number' },
    y: { type: 'number' },
    scale: { type: 'number' },
    rotation: { type: 'number' }
  },
  additionalProperties: false
};

export const getAgentTools = () => Object.freeze([
  {
    name: 'update_settings',
    description: 'Update the global project settings.',
    parameters: {
      type: 'object',
      properties: {
        aspectRatio: { type: 'string', description: 'e.g., "16:9", "9:16", "1:1"' },
        fps: { type: 'number' },
        backgroundColor: { type: 'string', description: 'Hex color string' }
      },
      additionalProperties: false
    }
  },
  {
    name: 'add_clip',
    description: 'Add a new video or image clip to the timeline.',
    parameters: {
      type: 'object',
      required: ['type', 'source', 'start', 'end'],
      properties: {
        type: { type: 'string', enum: ['video', 'image'] },
        source: { type: 'string', description: 'URI or path to the media source' },
        start: { type: 'number', description: 'Start time in seconds on the timeline' },
        end: { type: 'number', description: 'End time in seconds on the timeline' },
        volume: { type: 'number', description: 'Volume level (video only)' },
        transform: TransformSchema
      },
      additionalProperties: false
    }
  },
  {
    name: 'remove_clip',
    description: 'Remove an existing video or image clip from the timeline.',
    parameters: {
      type: 'object',
      required: ['targetId'],
      properties: { targetId: { type: 'string', description: 'ID of the node to remove' } },
      additionalProperties: false
    }
  },
  {
    name: 'trim_clip',
    description: 'Adjust the start or end times of an existing clip on the timeline.',
    parameters: {
      type: 'object',
      required: ['targetId'],
      properties: {
        targetId: { type: 'string' },
        start: { type: 'number' },
        end: { type: 'number' }
      },
      additionalProperties: false
    }
  },
  {
    name: 'split_clip',
    description: 'Split an existing clip into two clips at a specific time.',
    parameters: {
      type: 'object',
      required: ['targetId', 'time'],
      properties: {
        targetId: { type: 'string' },
        time: { type: 'number', description: 'Time in seconds where the split occurs' }
      },
      additionalProperties: false
    }
  },
  {
    name: 'add_text',
    description: 'Add a new text overlay to the timeline.',
    parameters: {
      type: 'object',
      required: ['content', 'start', 'end'],
      properties: {
        content: { type: 'string' },
        start: { type: 'number' },
        end: { type: 'number' },
        fontSize: { type: 'number' },
        fontFamily: { type: 'string' },
        color: { type: 'string' },
        backgroundColor: { type: 'string' },
        transform: TransformSchema
      },
      additionalProperties: false
    }
  },
  {
    name: 'update_text',
    description: 'Update the properties of an existing text overlay.',
    parameters: {
      type: 'object',
      required: ['targetId'],
      properties: {
        targetId: { type: 'string' },
        content: { type: 'string' },
        fontSize: { type: 'number' },
        fontFamily: { type: 'string' },
        color: { type: 'string' },
        backgroundColor: { type: 'string' },
        transform: TransformSchema
      },
      additionalProperties: false
    }
  },
  {
    name: 'remove_text',
    description: 'Remove an existing text overlay.',
    parameters: {
      type: 'object',
      required: ['targetId'],
      properties: { targetId: { type: 'string' } },
      additionalProperties: false
    }
  },
  {
    name: 'add_animation',
    description: 'Apply an entrance or exit animation to any layer.',
    parameters: {
      type: 'object',
      required: ['targetId', 'animation'],
      properties: {
        targetId: { type: 'string' },
        animation: { type: 'string', enum: ['fade_in', 'fade_out', 'slide_in', 'slide_out', 'scale_in', 'scale_out'] }
      },
      additionalProperties: false
    }
  },
  {
    name: 'remove_animation',
    description: 'Remove an animation from a layer.',
    parameters: {
      type: 'object',
      required: ['targetId'],
      properties: { targetId: { type: 'string' } },
      additionalProperties: false
    }
  },
  {
    name: 'set_blend_mode',
    description: 'Set the composite blend mode and opacity for any layer.',
    parameters: {
      type: 'object',
      required: ['targetId', 'blendMode'],
      properties: {
        targetId: { type: 'string' },
        blendMode: { type: 'string', enum: ['normal', 'multiply', 'screen', 'overlay', 'darken', 'lighten'] },
        opacity: { type: 'number', description: '0.0 to 1.0' }
      },
      additionalProperties: false
    }
  },
  {
    name: 'add_audio',
    description: 'Add an audio clip (music, SFX) to the timeline.',
    parameters: {
      type: 'object',
      required: ['source', 'start', 'end'],
      properties: {
        source: { type: 'string' },
        start: { type: 'number' },
        end: { type: 'number' },
        volume: { type: 'number' }
      },
      additionalProperties: false
    }
  },
  {
    name: 'adjust_volume',
    description: 'Adjust the volume of a specific audio or video clip.',
    parameters: {
      type: 'object',
      required: ['targetId', 'volume'],
      properties: {
        targetId: { type: 'string' },
        volume: { type: 'number', description: '0.0 to 1.0+' }
      },
      additionalProperties: false
    }
  }
]);
