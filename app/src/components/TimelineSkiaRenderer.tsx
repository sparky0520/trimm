import React from 'react';
import { Group, Rect, Text as SkiaText, Skia, SkTypeface } from '@shopify/react-native-skia';
import { useDerivedValue } from 'react-native-reanimated';
import { ProjectSchema, TextNode, OverlayNode, TimelineNode } from '../types/schema';
import { getAnimatedOpacity, getAnimatedTransform } from '../utils/animations';

interface Props {
  schema: ProjectSchema;
  currentTime: any; // SharedValue<number>
  width: number;
  height: number;
  typeface: SkTypeface | null;
}

const NodeRenderer = ({ node, currentTime, width, height, typeface }: { node: TimelineNode, currentTime: any, width: number, height: number, typeface: SkTypeface | null }) => {
  const animatedOpacity = useDerivedValue(() => getAnimatedOpacity(node, currentTime.value));
  const animatedTransform = useDerivedValue(() => {
    const t = getAnimatedTransform(node, currentTime.value, width, height) || [];
    if (t.length === 0) return [{ scale: 1 }];
    return t.map((op: any) => {
      if (op.rotate !== undefined) {
        return { rotate: typeof op.rotate === 'string' ? parseFloat(op.rotate) : op.rotate };
      }
      return op;
    });
  });
  const textOpacityMultiplier = node.type === 'text' ? ((node as TextNode).opacity ?? 1) : 1;
  const bgOpacity = useDerivedValue(() => animatedOpacity.value * textOpacityMultiplier);

  if (node.type === 'video' || node.type === 'audio') return null;

  if (node.type === 'overlay') {
    const overlayNode = node as OverlayNode;
    const color = overlayNode.color || 'rgba(0,0,0,0.5)';
    return (
      <Group opacity={animatedOpacity}>
        <Rect x={0} y={0} width={width} height={height} color={color} />
      </Group>
    );
  }

  if (node.type === 'text' && typeface) {
    const textNode = node as TextNode;
    const fontSize = textNode.fontSize || 32;
    const font = Skia.Font(typeface, fontSize);
    const textWidth = font.getTextWidth(textNode.content);
    
    const defaultX = width / 2 - textWidth / 2;
    const defaultY = height / 2 - fontSize / 2;
    const x = textNode.transform?.x !== undefined ? 0 : defaultX;
    const y = textNode.transform?.y !== undefined ? 0 : defaultY;
    const color = textNode.color || 'white';

    return (
      <Group transform={animatedTransform as any} opacity={animatedOpacity}>
        {textNode.backgroundColor && (
          <Rect 
            x={x - 5} y={y - fontSize + 5} 
            width={textWidth + 10} height={fontSize + 10} 
            color={textNode.backgroundColor} 
            opacity={bgOpacity}
          />
        )}
        <SkiaText x={x} y={y} text={textNode.content} font={font} color={color} opacity={bgOpacity} />
      </Group>
    );
  }

  return null;
};

export const TimelineSkiaRenderer = ({ schema, currentTime, width, height, typeface }: Props) => {
  return (
    <>
      {schema.timeline.map(node => (
        <NodeRenderer key={node.id} node={node} currentTime={currentTime} width={width} height={height} typeface={typeface} />
      ))}
    </>
  );
};
