import { AnimationType, Transform, BlendMode } from './schema';

export interface BaseToolCall {
  tool: string;
}

// Global Settings
export interface UpdateSettingsCall extends BaseToolCall {
  tool: 'update_settings';
  aspectRatio?: string;
  fps?: number;
  backgroundColor?: string;
}

// Media Clips (Video/Image)
export interface AddClipCall extends BaseToolCall {
  tool: 'add_clip';
  type: 'video' | 'image';
  source: string;
  start: number;
  end: number;
  volume?: number; // only for video
  transform?: Transform;
}

export interface RemoveClipCall extends BaseToolCall {
  tool: 'remove_clip';
  targetId: string;
}

export interface TrimClipCall extends BaseToolCall {
  tool: 'trim_clip';
  targetId: string;
  start?: number;
  end?: number;
}

export interface SplitClipCall extends BaseToolCall {
  tool: 'split_clip';
  targetId: string;
  time: number; // point to split at
}

// Text
export interface AddTextCall extends BaseToolCall {
  tool: 'add_text';
  content: string;
  start: number;
  end: number;
  fontSize?: number;
  fontFamily?: string;
  color?: string;
  backgroundColor?: string;
  transform?: Transform;
}

export interface UpdateTextCall extends BaseToolCall {
  tool: 'update_text';
  targetId: string;
  content?: string;
  fontSize?: number;
  fontFamily?: string;
  color?: string;
  backgroundColor?: string;
  transform?: Transform;
}

export interface RemoveTextCall extends BaseToolCall {
  tool: 'remove_text';
  targetId: string;
}

// Animations & Effects
export interface AddAnimationCall extends BaseToolCall {
  tool: 'add_animation';
  targetId: string;
  animation: AnimationType;
}

export interface RemoveAnimationCall extends BaseToolCall {
  tool: 'remove_animation';
  targetId: string;
}

export interface SetBlendModeCall extends BaseToolCall {
  tool: 'set_blend_mode';
  targetId: string;
  blendMode: BlendMode;
  opacity?: number;
}

// Audio
export interface AddAudioCall extends BaseToolCall {
  tool: 'add_audio';
  source: string;
  start: number;
  end: number;
  volume?: number;
}

export interface AdjustVolumeCall extends BaseToolCall {
  tool: 'adjust_volume';
  targetId: string;
  volume: number;
}

export type AgentToolCall = 
  | UpdateSettingsCall 
  | AddClipCall | RemoveClipCall | TrimClipCall | SplitClipCall
  | AddTextCall | UpdateTextCall | RemoveTextCall 
  | AddAnimationCall | RemoveAnimationCall | SetBlendModeCall
  | AddAudioCall | AdjustVolumeCall;
