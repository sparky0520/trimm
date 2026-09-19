import React, { useRef, useState, useEffect } from 'react';
import { View, StyleSheet, Dimensions } from 'react-native';
import { Canvas, Text as SkiaText, useFont, Group, Rect, SkFont } from '@shopify/react-native-skia';
import { SharedValue, useAnimatedReaction, useDerivedValue, interpolate, Extrapolation, runOnJS, useAnimatedStyle } from 'react-native-reanimated';
import Animated from 'react-native-reanimated';
import Video, { VideoRef } from 'react-native-video';
import { useProjectStore } from '../store/projectStore';
import { TimelineNode, TextNode, OverlayNode, Transform, VideoNode } from '../types/schema';

const { width, height: screenHeight } = Dimensions.get('window');

interface PreviewEngineProps {
  currentTime: SharedValue<number>;
  isPlaying: boolean;
}

const NativeVideoLayer = ({ video, currentTime, isPlaying, videoRef }: { video: VideoNode, currentTime: SharedValue<number>, isPlaying: boolean, videoRef: (r: VideoRef | null) => void }) => {
  const isWithinTime = useDerivedValue(() => {
    return currentTime.value >= video.start && currentTime.value <= video.end;
  });

  const [isVisible, setIsVisible] = useState(false);
  
  useAnimatedReaction(
    () => isWithinTime.value,
    (within) => {
      runOnJS(setIsVisible)(within);
    }
  );

  const animatedOpacity = useDerivedValue(() => {
    if (!isWithinTime.value) return 0;
    
    let baseOpacity = video.opacity !== undefined ? video.opacity : 1;
    
    if (video.animation === 'fade_in') {
      const fadeVal = interpolate(currentTime.value, [video.start, video.start + 1], [0, 1], Extrapolation.CLAMP);
      return baseOpacity * fadeVal;
    } else if (video.animation === 'fade_out') {
      const fadeVal = interpolate(currentTime.value, [video.end - 1, video.end], [1, 0], Extrapolation.CLAMP);
      return baseOpacity * fadeVal;
    }

    return baseOpacity;
  });

  const animatedTransform = useDerivedValue(() => {
    const ops: any[] = [];
    
    if (video.transform) {
      if (video.transform.x !== undefined) ops.push({ translateX: video.transform.x });
      if (video.transform.y !== undefined) ops.push({ translateY: video.transform.y });
      if (video.transform.scale !== undefined) ops.push({ scale: video.transform.scale });
      if (video.transform.rotation !== undefined) ops.push({ rotate: `${video.transform.rotation}deg` });
    }

    if (video.animation === 'slide_in') {
      const slideX = interpolate(currentTime.value, [video.start, video.start + 1], [-width, 0], Extrapolation.CLAMP);
      ops.push({ translateX: slideX });
    } else if (video.animation === 'scale_in') {
      const scaleVal = interpolate(currentTime.value, [video.start, video.start + 1], [0, 1], Extrapolation.CLAMP);
      ops.push({ scale: scaleVal });
    }

    return ops;
  });

  const animatedStyle = useAnimatedStyle(() => ({
    opacity: animatedOpacity.value,
    transform: animatedTransform.value,
    display: isWithinTime.value ? 'flex' : 'none',
  }));

  return (
    <Animated.View style={[styles.mockVideoContainer, animatedStyle]}>
      <Video
        ref={videoRef}
        source={{ uri: video.src }}
        style={StyleSheet.absoluteFill}
        paused={!isPlaying || !isVisible}
        muted={video.volume === 0}
        volume={video.volume ?? 1}
        resizeMode="contain"
        controls={true}
      />
    </Animated.View>
  );
};

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
    const defaultY = screenHeight * 0.25;
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
        <Rect x={0} y={0} width={width} height={screenHeight} color={overlayNode.color || 'rgba(0,0,0,0.5)'} blendMode={overlayNode.blendMode === 'normal' ? 'srcOver' : (overlayNode.blendMode || 'srcOver') as any} />
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

  // Handle frame-by-frame scrubbing and timeline looping
  useAnimatedReaction(
    () => currentTime.value,
    (time, previousTime) => {
      if (!isPlaying) {
        runOnJS(handleSeek)(time);
      } else if (previousTime !== null && time < previousTime) {
        // Timeline looped back to start, sync videos
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
        if (ref) {
          const sStart = video.sourceStart || 0;
          if (time < video.start) {
            ref.seek(sStart);
          } else if (time >= video.start && time <= video.end) {
            ref.seek((time - video.start) + sStart);
          }
        }
      });
    }
  };

  return (
    <View style={[styles.container, { backgroundColor: schema.settings.backgroundColor }]}>
      <View style={styles.videoUnderlay}>
        {schema.timeline.filter(n => n.type === 'video').map(video => (
          <NativeVideoLayer
            key={video.id}
            video={video as VideoNode}
            currentTime={currentTime}
            isPlaying={isPlaying}
            videoRef={(r) => { videoRefs.current[video.id] = r; }}
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
  container: { flex: 1, width: '100%', overflow: 'hidden', position: 'relative' },
  videoUnderlay: { position: 'absolute', top: 0, bottom: 0, left: 0, right: 0, backgroundColor: 'transparent', alignItems: 'center', justifyContent: 'center' },
  mockVideoContainer: { position: 'absolute', top: 0, bottom: 0, left: 0, right: 0, width: '100%', height: '100%' },
  canvas: { position: 'absolute', top: 0, bottom: 0, left: 0, right: 0 },
});
