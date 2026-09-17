import { z } from 'zod';

const transformSchema = z.object({
  x: z.number().optional(),
  y: z.number().optional(),
  scale: z.number().optional(),
  rotation: z.number().optional(),
});

const updateSettingsSchema = z.object({
  tool: z.literal('update_settings'),
  aspectRatio: z.string().optional(),
  fps: z.number().optional(),
  backgroundColor: z.string().optional(),
});

const addClipSchema = z.object({
  tool: z.literal('add_clip'),
  type: z.enum(['video', 'image']),
  source: z.string(),
  start: z.number(),
  end: z.number(),
  volume: z.number().optional(),
  transform: transformSchema.optional(),
});

const removeClipSchema = z.object({
  tool: z.literal('remove_clip'),
  targetId: z.string(),
});

const trimClipSchema = z.object({
  tool: z.literal('trim_clip'),
  targetId: z.string(),
  start: z.number().optional(),
  end: z.number().optional(),
});

const splitClipSchema = z.object({
  tool: z.literal('split_clip'),
  targetId: z.string(),
  time: z.number(),
});

const addTextSchema = z.object({
  tool: z.literal('add_text'),
  content: z.string(),
  start: z.number(),
  end: z.number(),
  fontSize: z.number().optional(),
  fontFamily: z.string().optional(),
  color: z.string().optional(),
  backgroundColor: z.string().optional(),
  transform: transformSchema.optional(),
});

const updateTextSchema = z.object({
  tool: z.literal('update_text'),
  targetId: z.string(),
  content: z.string().optional(),
  fontSize: z.number().optional(),
  fontFamily: z.string().optional(),
  color: z.string().optional(),
  backgroundColor: z.string().optional(),
  transform: transformSchema.optional(),
});

const removeTextSchema = z.object({
  tool: z.literal('remove_text'),
  targetId: z.string(),
});

const addAnimationSchema = z.object({
  tool: z.literal('add_animation'),
  targetId: z.string(),
  animation: z.enum(['fade_in', 'fade_out', 'slide_in', 'slide_out', 'scale_in', 'scale_out']),
});

const removeAnimationSchema = z.object({
  tool: z.literal('remove_animation'),
  targetId: z.string(),
});

const setBlendModeSchema = z.object({
  tool: z.literal('set_blend_mode'),
  targetId: z.string(),
  blendMode: z.enum(['normal', 'multiply', 'screen', 'overlay', 'darken', 'lighten']),
  opacity: z.number().optional(),
});

const addAudioSchema = z.object({
  tool: z.literal('add_audio'),
  source: z.string(),
  start: z.number(),
  end: z.number(),
  volume: z.number().optional(),
});

const adjustVolumeSchema = z.object({
  tool: z.literal('adjust_volume'),
  targetId: z.string(),
  volume: z.number(),
});

export const agentToolCallSchema = z.discriminatedUnion('tool', [
  updateSettingsSchema,
  addClipSchema,
  removeClipSchema,
  trimClipSchema,
  splitClipSchema,
  addTextSchema,
  updateTextSchema,
  removeTextSchema,
  addAnimationSchema,
  removeAnimationSchema,
  setBlendModeSchema,
  addAudioSchema,
  adjustVolumeSchema,
]);

export function validateToolCall(payload: unknown) {
  return agentToolCallSchema.parse(payload);
}
