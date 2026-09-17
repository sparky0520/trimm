export type AnimationType = 'fade_in' | 'fade_out' | 'slide_in' | 'slide_out' | 'scale_in' | 'scale_out';
export type BlendMode = 'normal' | 'multiply' | 'screen' | 'overlay' | 'darken' | 'lighten';

export interface Transform {
  x?: number;
  y?: number;
  scale?: number;
  rotation?: number; // in degrees
}

export interface BaseNode {
  id: string;
  start: number; // in seconds
  end: number;   // in seconds
  animation?: AnimationType;
  opacity?: number; // 0 to 1
  blendMode?: BlendMode;
}

export interface VideoNode extends BaseNode {
  type: 'video';
  src: string;
  volume?: number;
  transform?: Transform;
}

export interface ImageNode extends BaseNode {
  type: 'image';
  src: string;
  transform?: Transform;
}

export interface TextNode extends BaseNode {
  type: 'text';
  content: string;
  fontSize?: number;
  fontFamily?: string;
  color?: string;
  backgroundColor?: string;
  transform?: Transform;
}

export interface AudioNode extends BaseNode {
  type: 'audio';
  src: string;
  volume?: number;
}

export interface OverlayNode extends BaseNode {
  type: 'overlay';
  color?: string; // e.g. for color grading or solid color overlays
}

export type TimelineNode = VideoNode | ImageNode | TextNode | AudioNode | OverlayNode;

export interface ProjectSettings {
  aspectRatio: string; // e.g., '16:9', '9:16', '1:1'
  fps: number;
  backgroundColor: string;
}

export interface ProjectSchema {
  settings: ProjectSettings;
  timeline: TimelineNode[];
}
