import React, { useRef } from 'react';
import { View, StyleSheet, Dimensions } from 'react-native';
import { Canvas, Text as SkiaText, useFont, Group, Rect, SkFont } from '@shopify/react-native-skia';
import { SharedValue, useAnimatedReaction, useDerivedValue, interpolate, Extrapolation, runOnJS } from 'react-native-reanimated';
import Video, { VideoRef } from 'react-native-video';
import { useProjectStore } from '../store/projectStore';
import { TimelineNode, TextNode, OverlayNode, Transform } from '../types/schema';

const { width } = Dimensions.get('window');
const HEIGHT = 300;

interface PreviewEngineProps {
  currentTime: SharedValue<number>;
  isPlaying: boolean;
}

// Sub-component to render an individual layer using derived values for visibility and animations
const SkiaLayer = ({ node, currentTime, font }: { node: TimelineNode, currentTime: SharedValue<number>, font: SkFont | null }) => {
  
  // Base visibility block
  const isWithinTime = useDerivedValue(() => {
    return currentTime.value >= node.start && currentTime.value <= node.end;
  });

  // Calculate animated opacity based on fade_in / fade_out
  const animatedOpacity = useDerivedValue(() => {
    if (!isWithinTime.value) return 0;
    
    let baseOpacity = node.opacity !== undefined ? node.opacity : 1;
    
    if (node.animation === 'fade_in') {
      // fade in over the first 1 second
      const fadeVal = interpolate(currentTime.value, [node.start, node.start + 1], [0, 1], Extrapolation.CLAMP);
      return baseOpacity * fadeVal;
    } else if (node.animation === 'fade_out') {
      // fade out over the last 1 second
      const fadeVal = interpolate(currentTime.value, [node.end - 1, node.end], [1, 0], Extrapolation.CLAMP);
      return baseOpacity * fadeVal;
    }

    return baseOpacity;
  });

  // Calculate animated transforms
  const animatedTransform = useDerivedValue(() => {
    const ops: any[] = [];
    
    // Apply static transforms from schema
    if (node.type === 'text' || node.type === 'image' || node.type === 'video') {
      const transform = (node as any).transform as Transform | undefined;
      if (transform) {
        if (transform.x !== undefined) ops.push({ translateX: transform.x });
        if (transform.y !== undefined) ops.push({ translateY: transform.y });
        if (transform.scale !== undefined) ops.push({ scale: transform.scale });
        if (transform.rotation !== undefined) ops.push({ rotate: (transform.rotation * Math.PI) / 180 });
      }
    }

    // Apply dynamic animation transforms
    if (node.animation === 'slide_in') {
      // Slide from left over 1 second
      const slideX = interpolate(currentTime.value, [node.start, node.start + 1], [-width, 0], Extrapolation.CLAMP);
      ops.push({ translateX: slideX });
    } else if (node.animation === 'scale_in') {
      // Scale from 0 to 1 over 1 second
      const scaleVal = interpolate(currentTime.value, [node.start, node.start + 1], [0, 1], Extrapolation.CLAMP);
      ops.push({ scale: scaleVal });
    }

    return ops.length > 0 ? ops : undefined;
  });


  if (node.type === 'text' && font) {
    const textNode = node as TextNode;
    const textWidth = font.getTextWidth(textNode.content);
    const defaultX = width / 2 - textWidth / 2;
    const defaultY = HEIGHT / 2;
    const x = textNode.transform?.x !== undefined ? 0 : defaultX;
    const y = textNode.transform?.y !== undefined ? 0 : defaultY;

    return (
      <Group opacity={animatedOpacity} transform={animatedTransform}>
        {textNode.backgroundColor && (
           <Rect x={x - 5} y={y - textNode.fontSize! + 5} width={textWidth + 10} height={textNode.fontSize! + 10} color={textNode.backgroundColor} />
        )}
        <SkiaText
          x={x}
          y={y}
          text={textNode.content}
          font={font}
          opacity={textNode.opacity ?? 1}
          blendMode={textNode.blendMode === 'normal' ? 'srcOver' : (textNode.blendMode || 'srcOver') as any}
        />
      </Group>
    );
  }

  if (node.type === 'overlay') {
    const overlayNode = node as OverlayNode;
    return (
      <Group opacity={animatedOpacity}>
        <Rect x={0} y={0} width={width} height={HEIGHT} color={overlayNode.color || 'rgba(0,0,0,0.5)'} blendMode={overlayNode.blendMode === 'normal' ? 'srcOver' : (overlayNode.blendMode || 'srcOver') as any} />
      </Group>
    );
  }

  return null;
};

export const PreviewEngine = ({ currentTime, isPlaying }: PreviewEngineProps) => {
  const schema = useProjectStore((state) => state.present);
  const font = useFont(require('../assets/fonts/Roboto.ttf'), 32); 
  const videoRefs = useRef<{ [key: string]: VideoRef | null }>({});
  const lastSeek = useRef(0);

  // Handle frame-by-frame scrubbing
  useAnimatedReaction(
    () => currentTime.value,
    (time) => {
      // Only manually seek if we are paused (scrubbing). If playing, let native player handle playback.
      if (!isPlaying) {
        runOnJS(handleSeek)(time);
      }
    }
  );

  const handleSeek = (time: number) => {
    const now = Date.now();
    if (now - lastSeek.current > 100) { // Throttle seek to ~10 times per second
      lastSeek.current = now;
      schema.timeline.filter(n => n.type === 'video').forEach(video => {
        const ref = videoRefs.current[video.id];
        if (ref && time >= video.start && time <= video.end) {
          ref.seek(time - video.start);
        }
      });
    }
  };

  return (
    <View style={[styles.container, { backgroundColor: schema.settings.backgroundColor }]}>
      <View style={styles.videoUnderlay}>
        {schema.timeline.filter(n => n.type === 'video').map(video => (
          <Video
            key={video.id}
            ref={(r) => { videoRefs.current[video.id] = r; }}
            source={{ uri: video.src }}
            style={styles.mockVideoContainer}
            paused={!isPlaying}
            muted={video.volume === 0}
            volume={video.volume ?? 1}
            resizeMode="cover"
          />
        ))}
      </View>

      <Canvas style={styles.canvas} pointerEvents="none">
        {schema.timeline.map((node) => (
          <SkiaLayer key={node.id} node={node} currentTime={currentTime} font={font} />
        ))}
      </Canvas>
    </View>
  );
};

const styles = StyleSheet.create({
  container: { width: '100%', height: HEIGHT, overflow: 'hidden', position: 'relative' },
  videoUnderlay: { position: 'absolute', top: 0, bottom: 0, left: 0, right: 0, backgroundColor: 'transparent', alignItems: 'center', justifyContent: 'center' },
  mockVideoContainer: { position: 'absolute', top: 0, bottom: 0, left: 0, right: 0, width: '100%', height: '100%' },
  canvas: { position: 'absolute', top: 0, bottom: 0, left: 0, right: 0 },
});
