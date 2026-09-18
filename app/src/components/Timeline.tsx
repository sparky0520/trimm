import React from 'react';
import { View, Text, StyleSheet, ScrollView } from 'react-native';
import { GestureDetector, Gesture } from 'react-native-gesture-handler';
import Animated, { useAnimatedStyle, SharedValue, runOnJS } from 'react-native-reanimated';
import { useProjectStore } from '../store/projectStore';
import { TimelineNode } from '../types/schema';
import { TIMELINE_MAX_SECONDS } from '../../App';

const PIXELS_PER_SECOND = 40; // Increased for better scrubbing
const contentWidth = TIMELINE_MAX_SECONDS * PIXELS_PER_SECOND;

interface TimelineProps {
  currentTime: SharedValue<number>;
  onScrubStart?: () => void;
}

export const Timeline = ({ currentTime, onScrubStart }: TimelineProps) => {
  const present = useProjectStore(state => state.present);

  const pan = Gesture.Pan()
    .onStart(() => {
      if (onScrubStart) {
        runOnJS(onScrubStart)();
      }
    })
    .onChange((event) => {
      const newTime = currentTime.value + event.changeX / PIXELS_PER_SECOND;
      currentTime.value = Math.max(0, Math.min(newTime, TIMELINE_MAX_SECONDS));
    });

  const playheadStyle = useAnimatedStyle(() => {
    return {
      transform: [{ translateX: currentTime.value * PIXELS_PER_SECOND }]
    };
  });

  const renderNode = (node: TimelineNode, index: number) => {
    const left = node.start * PIXELS_PER_SECOND;
    const itemWidth = (node.end - node.start) * PIXELS_PER_SECOND;

    let color = '#3F3F46'; // Default Border color
    let label = node.type;

    if (node.type === 'video') color = '#1B365D'; // UserBlue
    if (node.type === 'text') {
      color = '#27272A'; // Raised
      label = (node as any).content;
    }
    if (node.type === 'audio') color = '#D4D4D8'; // Lavender

    return (
      <View
        key={node.id}
        style={[
          styles.node,
          { left, width: itemWidth, backgroundColor: color, top: index * 40 }
        ]}
      >
        <Text style={[styles.nodeText, { color: node.type === 'text' ? '#FAFAFA' : '#FAFAFA' }]} numberOfLines={1}>{label}</Text>
      </View>
    );
  };

  return (
    <View style={styles.container}>
      <ScrollView horizontal contentContainerStyle={{ width: contentWidth }}>
        <GestureDetector gesture={pan}>
          <View style={styles.timelineArea}>
            <View style={styles.ruler}>
              {Array.from({ length: TIMELINE_MAX_SECONDS + 1 }).map((_, i) => (
                <View key={i} style={[styles.tick, { left: i * PIXELS_PER_SECOND }]}>
                  {i % 2 === 0 && <Text style={styles.tickText}>{i}s</Text>}
                </View>
              ))}
            </View>
            
            <View style={styles.layers}>
              {present.timeline.map((node, index) => renderNode(node, index))}
            </View>

            {/* Playhead */}
            <Animated.View style={[styles.playhead, playheadStyle]}>
              <View style={styles.playheadHead} />
              <View style={styles.playheadLine} />
            </Animated.View>
          </View>
        </GestureDetector>
      </ScrollView>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    height: 180,
    backgroundColor: '#09090B',
    borderTopWidth: 1,
    borderTopColor: '#3F3F46',
  },
  timelineArea: {
    flex: 1,
    position: 'relative',
    height: '100%',
  },
  ruler: {
    height: 20,
    backgroundColor: '#18181B',
    borderBottomWidth: 1,
    borderBottomColor: '#3F3F46',
  },
  tick: {
    position: 'absolute',
    width: 1,
    height: 10,
    backgroundColor: '#3F3F46',
    top: 10,
  },
  tickText: {
    position: 'absolute',
    top: -10,
    left: 2,
    color: '#A1A1AA',
    fontSize: 8,
  },
  layers: {
    flex: 1,
    paddingTop: 10,
    position: 'relative',
  },
  node: {
    position: 'absolute',
    height: 30,
    borderRadius: 4,
    padding: 4,
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: '#3F3F46',
  },
  nodeText: {
    fontSize: 10,
    fontWeight: 'bold',
  },
  playhead: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    width: 20,
    marginLeft: -10, // Center on the line
    alignItems: 'center',
    zIndex: 10,
  },
  playheadHead: {
    width: 10,
    height: 10,
    backgroundColor: '#FAFAFA', // Coral was 0xFFFAFAFA
    borderRadius: 5,
  },
  playheadLine: {
    width: 2,
    flex: 1,
    backgroundColor: '#FAFAFA', // Coral was 0xFFFAFAFA
  },
});
