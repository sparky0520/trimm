import React, { useRef, useState, useEffect } from 'react';
import { View, StyleSheet, Dimensions } from 'react-native';
import { Canvas, Skia, useTypeface } from '@shopify/react-native-skia';
import { SharedValue, useAnimatedReaction, useDerivedValue, runOnJS, useAnimatedStyle } from 'react-native-reanimated';
import Animated from 'react-native-reanimated';
import Video, { VideoRef } from 'react-native-video';
import { useProjectStore } from '../store/projectStore';
import { VideoNode } from '../types/schema';
import { getAnimatedOpacity, getAnimatedTransform } from '../utils/animations';
import { drawTimelineToCanvas } from '../utils/canvasRenderer';
import { TimelineSkiaRenderer } from './TimelineSkiaRenderer';

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

  const animatedOpacity = useDerivedValue(() => getAnimatedOpacity(video, currentTime.value));
  const animatedTransform = useDerivedValue(() => getAnimatedTransform(video, currentTime.value, width, screenHeight) || []);

  const animatedStyle = useAnimatedStyle(() => {
    return {
      opacity: animatedOpacity.value,
      top: isWithinTime.value ? 0 : 9999,
      transform: (animatedTransform.value && animatedTransform.value.length > 0)
        ? (animatedTransform.value as any)
        : [{ scale: 1 }],
    };
  });

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

export const PreviewEngine = ({ currentTime, isPlaying }: PreviewEngineProps) => {
  const schema = useProjectStore((state) => state.present);
  const typeface = useTypeface(require('../assets/fonts/Roboto.ttf')); 
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
    },
    [isPlaying]
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
        <TimelineSkiaRenderer schema={schema} currentTime={currentTime} width={width} height={screenHeight} typeface={typeface} />
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
