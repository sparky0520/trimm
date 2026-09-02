import { z } from 'zod';

const finiteNumber = z.number().finite();
const seconds = finiteNumber.nonnegative();
const id = z.string().min(1);
// OpenAI structured outputs accepts a single schema in `items`, but not the
// draft-7 tuple form where `items` is an array of schemas. A fixed-length
// homogeneous array preserves the runtime contract while producing a
// provider-compatible JSON Schema.
const normalizedPoint = z.array(finiteNumber.min(0).max(1)).length(2);
const trimRange = z.array(seconds).length(2);
const range = z.object({ start: seconds, end: seconds }).strict().refine(({ start, end }) => end > start, { path: ['end'], message: 'end must be greater than start' });
const audioPolicy = z.enum(['source', 'mute']);
const ripple = z.enum(['none', 'insert', 'close-gap']);
const normalizedRect = z.object({ x: finiteNumber.min(0).max(1), y: finiteNumber.min(0).max(1), width: finiteNumber.min(0.2).max(1), height: finiteNumber.min(0.2).max(1) }).strict()
  .refine(({ x, width }) => x + width <= 1, { path: ['width'], message: 'crop exceeds source width' })
  .refine(({ y, height }) => y + height <= 1, { path: ['height'], message: 'crop exceeds source height' });
const cameraTransform = z.object({
  crop: normalizedRect.optional(), rotation: finiteNumber.min(-15).max(360).optional(),
  safeRegions: z.array(safeRegion).max(20), evidence: z.string().min(1),
}).strict().refine((value) => value.crop || value.rotation != null, { message: 'at least one camera correction is required' });

const motionBase = { itemId: id, start: seconds, end: seconds, reducedMotion: z.enum(['full', 'reduce']), intent: z.enum(['introduce', 'point-out', 'transition', 'recap']) };
const motionRange = (schema) => schema.refine(({ start, end }) => end > start, { path: ['end'], message: 'end must be greater than start' });
const targetRect = z.object({ x: finiteNumber.min(0).max(0.95), y: finiteNumber.min(0).max(0.95), width: finiteNumber.min(0.05).max(1), height: finiteNumber.min(0.05).max(1) }).strict()
  .refine(({ x, width }) => x + width <= 1).refine(({ y, height }) => y + height <= 1);
const targetEvidence = z.object({ observationId: id, timestamp: seconds, description: z.string().min(1) }).strict();

const timeRange = z.object({
  start: seconds,
  end: seconds,
}).strict().refine(({ start, end }) => end > start, {
  path: ['end'],
  message: 'end must be greater than start',
});

const operation = (type, shape) => z.object({
  type: z.literal(type),
  ...shape,
}).strict();

export const RemoveRangesOperationSchema = operation('remove_ranges', {
  sourceId: id,
  ranges: z.array(timeRange).min(1),
});

export const SetSpeedOperationSchema = operation('set_speed', {
  itemId: id,
  speed: finiteNumber.positive(),
});

export const SetVolumeOperationSchema = operation('set_volume', {
  itemId: id,
  volume: finiteNumber.min(0).max(2),
});

export const AddZoomOperationSchema = operation('add_zoom', {
  itemId: id,
  sourceId: id,
  start: seconds,
  end: seconds,
  scale: finiteNumber.min(1).max(4),
  focus: normalizedPoint,
}).refine(({ start, end }) => end > start, {
  path: ['end'],
  message: 'end must be greater than start',
});

export const AddTitleOperationSchema = operation('add_title', {
  itemId: id,
  start: seconds,
  end: seconds,
  value: z.string().min(1),
  preset: z.enum(['title-card', 'lower-third']),
}).refine(({ start, end }) => end > start, {
  path: ['end'],
  message: 'end must be greater than start',
});

const caption = z.object({
  itemId: id,
  start: seconds,
  end: seconds,
  value: z.string().min(1),
}).strict().refine(({ start, end }) => end > start, {
  path: ['end'],
  message: 'end must be greater than start',
});

export const AddCaptionsOperationSchema = operation('add_captions', {
  sourceId: id,
  captions: z.array(caption).min(0),
  target: z.object({ sourceId: id, startMs: seconds, endMs: seconds }).optional(),
  speakerId: id.optional(),
});

const itemPatch = z.object({
  label: z.string().optional(),
  start: seconds.optional(),
  end: seconds.optional(),
  trim: trimRange.optional(),
  speed: finiteNumber.positive().optional(),
  volume: finiteNumber.min(0).max(2).optional(),
  scale: finiteNumber.min(1).max(4).optional(),
  focus: normalizedPoint.optional(),
  value: z.string().min(1).optional(),
  preset: z.enum(['title-card', 'caption', 'lower-third']).optional(),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(),
  src: z.string().min(1).optional(),
}).strict().refine((patch) => Object.keys(patch).length > 0, {
  message: 'changes must contain at least one field',
});

export const UpdateItemOperationSchema = operation('update_item', {
  itemId: id,
  changes: itemPatch,
});

export const RemoveItemOperationSchema = operation('remove_item', {
  itemId: id,
  ripple: z.enum(['none', 'close-gap']).optional(),
});

export const InsertClipOperationSchema = operation('insert_clip', {
  itemId: id, assetId: id, sourceRange: range, timelineRange: range,
  audioPolicy, ripple: z.enum(['none', 'insert']),
});

export const ReplaceVisualOperationSchema = operation('replace_visual', {
  itemId: id, targetItemId: id, assetId: id, sourceRange: range,
  timelineRange: range, audioPolicy,
});

export const TrimItemOperationSchema = operation('trim_item', {
  itemId: id, sourceRange: range, timelineRange: range,
  ripple: z.enum(['none', 'close-gap']),
});

export const MoveItemOperationSchema = operation('move_item', {
  itemId: id, timelineRange: range, ripple: z.enum(['none', 'insert']),
});



const speechRange = range;
const audioAdjustment = z.object({
  itemId: id,
  role: z.enum(['dialogue', 'bed']),
  gainDb: finiteNumber.min(-24).max(12),
  measuredLufs: finiteNumber.min(-70).max(0).nullable(),
  speechRanges: z.array(speechRange).max(200),
}).strict();

export const AutoAudioDuckingOperationSchema = operation('auto_audio_ducking', {
  targetLufs: z.union([z.literal(-16), z.literal(-18), z.literal(-20)]),
  duckingDb: finiteNumber.min(3).max(18),
  attackSeconds: finiteNumber.min(0.05).max(0.5),
  releaseSeconds: finiteNumber.min(0.05).max(0.75),
  adjustments: z.array(audioAdjustment).min(1).max(100),
});

export const ClearAudioLevelsOperationSchema = operation('clear_audio_levels', {
  itemIds: z.array(id).min(1).max(100),
});

const musicSegment = z.object({
  sourceStart: seconds, sourceEnd: seconds, timelineStart: seconds, timelineEnd: seconds,
  fadeInSeconds: finiteNumber.min(0).max(2), fadeOutSeconds: finiteNumber.min(0).max(2),
}).strict().refine((value) => value.sourceEnd > value.sourceStart && value.timelineEnd > value.timelineStart, { message: 'music segment ranges must increase' });
const shiftedCut = z.object({ cutId: id, from: seconds, to: seconds, shiftSeconds: finiteNumber.min(-0.5).max(0.5), applied: z.boolean(), reason: z.enum(['strong-beat', 'speech-protected', 'no-strong-beat-in-tolerance']) }).strict();
export const SetMusicEditOperationSchema = operation('set_music_edit', {
  itemId: id, strategy: z.enum(['simple-fade', 'loop', 'phrase-ending', 'intentional-fade']),
  segments: z.array(musicSegment).min(1).max(100), shiftedCuts: z.array(shiftedCut).max(500),
});
export const ClearMusicEditOperationSchema = operation('clear_music_edit', { itemId: id });

export const SetRotationOperationSchema = operation('set_rotation', { itemId: id, rotation: finiteNumber.min(-15).max(360) });
export const ClearRotationOperationSchema = operation('clear_rotation', { itemId: id });
export const SetCropOperationSchema = operation('set_crop', { itemId: id, crop: normalizedRect });
export const ClearCropOperationSchema = operation('clear_crop', { itemId: id });
export const AddFeatureTitleOperationSchema = motionRange(operation('add_feature_title', { ...motionBase, value: z.string().min(1).max(80), preset: z.enum(['product', 'feature']) }));
export const AddCalloutOperationSchema = operation('add_callout', {
  text: z.string().min(1),
  target: normalizedRect,
  range: timeRange.optional(),
});

export const AddSpotlightOperationSchema = operation('add_spotlight', {
  target: normalizedRect,
  range: timeRange.optional(),
});
export const AddPushOperationSchema = motionRange(operation('add_push', { ...motionBase, target: targetRect, evidence: targetEvidence, direction: z.enum(['in', 'out']), preset: z.enum(['subtle', 'standard']) }));
export const AddSectionTransitionOperationSchema = motionRange(operation('add_section_transition', { ...motionBase, preset: z.enum(['cut', 'dip', 'slide']), direction: z.enum(['left', 'right']).optional() }));
export const AddDeviceFrameOperationSchema = motionRange(operation('add_device_frame', { ...motionBase, target: targetRect, evidence: targetEvidence, preset: z.enum(['screen', 'device']) }));

export const EditInstructionSchema = z.discriminatedUnion('type', [
  RemoveRangesOperationSchema,
  SetSpeedOperationSchema,
  SetVolumeOperationSchema,
  AddZoomOperationSchema,
  AddTitleOperationSchema,
  AddCaptionsOperationSchema,
  UpdateItemOperationSchema,
  RemoveItemOperationSchema,
  InsertClipOperationSchema,
  ReplaceVisualOperationSchema,
  TrimItemOperationSchema,
  MoveItemOperationSchema,

   AutoAudioDuckingOperationSchema,
  ClearAudioLevelsOperationSchema,
  SetMusicEditOperationSchema,
  ClearMusicEditOperationSchema,
  SetRotationOperationSchema,
  ClearRotationOperationSchema,
  SetCropOperationSchema,
  ClearCropOperationSchema,
  AddFeatureTitleOperationSchema,
  AddCalloutOperationSchema,
  AddSpotlightOperationSchema,
  AddPushOperationSchema,
  AddSectionTransitionOperationSchema,
  AddDeviceFrameOperationSchema,
]);

export const EditInstructionBatchSchema = z.array(EditInstructionSchema).min(1);

export function parseEditInstructions(input) {
  return EditInstructionBatchSchema.parse(input);
}

/** JSON Schema suitable for a model structured-output response property. */
export function editInstructionsJsonSchema() {
  return z.toJSONSchema(EditInstructionBatchSchema, {
    target: 'draft-7',
    unrepresentable: 'throw',
  });
}
