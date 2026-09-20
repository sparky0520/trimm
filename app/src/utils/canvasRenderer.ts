import { Skia, SkCanvas, SkTypeface } from '@shopify/react-native-skia';
import { ProjectSchema, TextNode, OverlayNode } from '../types/schema';
import { getAnimatedOpacity, getAnimatedTransform } from './animations';

export const drawTimelineToCanvas = (
  canvas: SkCanvas,
  schema: ProjectSchema,
  time: number,
  width: number,
  height: number,
  typeface: SkTypeface | null
) => {
  'worklet';

  // Clear canvas for transparent background
  canvas.clear(Skia.Color('transparent'));

  for (const node of schema.timeline) {
    const opacity = getAnimatedOpacity(node, time);
    if (opacity <= 0 || time < node.start || time > node.end) continue;

    canvas.save();
    
    // Apply transforms
    const transforms = getAnimatedTransform(node, time, width, height);
    if (transforms) {
      for (const t of transforms) {
        if (t.translateX !== undefined) canvas.translate(t.translateX, 0);
        if (t.translateY !== undefined) canvas.translate(0, t.translateY);
        if (t.scale !== undefined) {
          canvas.translate(width/2, height/2);
          canvas.scale(t.scale, t.scale);
          canvas.translate(-width/2, -height/2);
        }
        if (t.rotate !== undefined) {
          const deg = typeof t.rotate === 'string' ? parseFloat(t.rotate) : t.rotate;
          canvas.translate(width/2, height/2);
          canvas.rotate(deg, 0, 0);
          canvas.translate(-width/2, -height/2);
        }
      }
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

      const paint = Skia.Paint();
      paint.setColor(Skia.Color(textNode.color || 'white'));
      paint.setAlphaf(opacity * (textNode.opacity ?? 1));

      if (textNode.backgroundColor) {
        const bgPaint = Skia.Paint();
        bgPaint.setColor(Skia.Color(textNode.backgroundColor));
        bgPaint.setAlphaf(opacity * (textNode.opacity ?? 1));
        canvas.drawRect({ x: x - 5, y: y - fontSize + 5, width: textWidth + 10, height: fontSize + 10 }, bgPaint);
      }

      canvas.drawText(textNode.content, x, y, paint, font);
    } else if (node.type === 'overlay') {
      const overlayNode = node as OverlayNode;
      const paint = Skia.Paint();
      paint.setColor(Skia.Color(overlayNode.color || 'rgba(0,0,0,0.5)'));
      paint.setAlphaf(opacity);
      canvas.drawRect({ x: 0, y: 0, width, height }, paint);
    }

    canvas.restore();
  }
};
