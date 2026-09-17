import React from 'react';
import { View, Text, StyleSheet, ScrollView } from 'react-native';
import { GestureDetector, Gesture } from 'react-native-gesture-handler';
import Animated, { useAnimatedStyle, SharedValue } from 'react-native-reanimated';
import { useProjectStore } from '../store/projectStore';
import { TimelineNode } from '../types/schema';
import { TIMELINE_MAX_SECONDS } from '../../App';

const PIXELS_PER_SECOND = 40; // Increased for better scrubbing
const contentWidth = TIMELINE_MAX_SECONDS * PIXELS_PER_SECOND;

interface TimelineProps {
  currentTime: SharedValue<number>;
  _onScrubStart?: () => void;
}

export const Timeline = ({ currentTime, _onScrubStart }: TimelineProps) => {
  const present = useProjectStore(state => state.present);

  const pan = Gesture.Pan()
    .onStart(() => {
      // Call JS callback to pause playback
      // Note: In Reanimated 3, calling JS from UI thread requires runOnJS.
      // For simplicity in this mock, we can just use the gesture to update the value.
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

    let color = '#555';
    let label = node.type;

    if (node.type === 'video') color = '#2196F3';
    if (node.type === 'text') {
      color = '#4CAF50';
      label = (node as any).content;
    }
    if (node.type === 'audio') color = '#FFC107';

    return (
      <View
        key={node.id}
        style={[
          styles.node,
          { left, width: itemWidth, backgroundColor: color, top: index * 40 }
        ]}
      >
        <Text style={styles.nodeText} numberOfLines={1}>{label}</Text>
      </View>
    );
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Timeline</Text>
      </View>
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
    height: 200,
    backgroundColor: '#1E1E1E',
    borderTopWidth: 1,
    borderTopColor: '#333',
  },
  header: {
    padding: 8,
    backgroundColor: '#2A2A2A',
    borderBottomWidth: 1,
    borderBottomColor: '#333',
  },
  title: {
    color: '#CCC',
    fontSize: 12,
    fontWeight: 'bold',
  },
  timelineArea: {
    flex: 1,
    position: 'relative',
    height: '100%',
  },
  ruler: {
    height: 20,
    backgroundColor: '#222',
    borderBottomWidth: 1,
    borderBottomColor: '#444',
  },
  tick: {
    position: 'absolute',
    width: 1,
    height: 10,
    backgroundColor: '#666',
    top: 10,
  },
  tickText: {
    position: 'absolute',
    top: -10,
    left: 2,
    color: '#888',
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
    borderColor: 'rgba(0,0,0,0.2)',
  },
  nodeText: {
    color: '#FFF',
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
    backgroundColor: '#F44336',
    borderRadius: 5,
  },
  playheadLine: {
    width: 2,
    flex: 1,
    backgroundColor: '#F44336',
  },
});
