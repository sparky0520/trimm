import { TimelineNode, Transform } from '../types/schema';

export const getAnimatedOpacity = (node: TimelineNode, time: number): number => {
  'worklet';
  if (time < node.start || time > node.end) return 0;

  const baseOpacity = node.opacity !== undefined ? node.opacity : 1;

  if (node.animation === 'fade_in') {
    if (time < node.start + 1) {
      const p = time - node.start; // 0 to 1 over 1 second
      return baseOpacity * p;
    }
  } else if (node.animation === 'fade_out') {
    if (time > node.end - 1) {
      const p = node.end - time; // 1 to 0 over last 1 second
      return baseOpacity * p;
    }
  }

  return baseOpacity;
};

export const getAnimatedTransform = (
  node: TimelineNode, 
  time: number, 
  width: number, 
  _height: number
): { translateX?: number; translateY?: number; scale?: number; rotate?: string | number }[] | undefined => {
  'worklet';
  if (time < node.start || time > node.end) return undefined;

  const ops: { translateX?: number; translateY?: number; scale?: number; rotate?: string | number }[] = [];

  // 1. Static transforms
  if (node.type === 'text' || node.type === 'image' || node.type === 'video') {
    const transform = (node as any).transform as Transform | undefined;
    if (transform) {
      if (transform.x !== undefined) ops.push({ translateX: transform.x });
      if (transform.y !== undefined) ops.push({ translateY: transform.y });
      if (transform.scale !== undefined) ops.push({ scale: transform.scale });
      if (transform.rotation !== undefined) ops.push({ rotate: `${transform.rotation}deg` });
    }
  }

  // 2. Dynamic animations
  if (node.animation === 'slide_in') {
    if (time < node.start + 1) {
      const p = time - node.start; // 0 to 1
      const slideX = -width + (width * p);
      ops.push({ translateX: slideX });
    }
  } else if (node.animation === 'slide_out') {
    if (time > node.end - 1) {
      const p = time - (node.end - 1); // 0 to 1
      const slideX = width * p;
      ops.push({ translateX: slideX });
    }
  } else if (node.animation === 'scale_in') {
    if (time < node.start + 1) {
      const p = time - node.start; // 0 to 1
      ops.push({ scale: p });
    }
  } else if (node.animation === 'scale_out') {
    if (time > node.end - 1) {
      const p = node.end - time; // 1 to 0
      ops.push({ scale: p });
    }
  }

  return ops.length > 0 ? ops : undefined;
};
