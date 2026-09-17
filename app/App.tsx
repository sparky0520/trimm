import React, { useEffect, useState } from 'react';
import { StyleSheet, View, Text, KeyboardAvoidingView, Platform, Button, TouchableOpacity, Alert } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { useSharedValue, withTiming, Easing, cancelAnimation, withRepeat } from 'react-native-reanimated';
import { PreviewEngine } from './src/components/PreviewEngine';
import { Timeline } from './src/components/Timeline';
import { Chat } from './src/components/Chat';
import { AssetPicker } from './src/components/AssetPicker';
import { useProjectStore } from './src/store/projectStore';
import { exportProject } from './src/services/exportService';

export const TIMELINE_MAX_SECONDS = 30;

function App(): React.JSX.Element {
  const currentTime = useSharedValue(0);
  const [isPlaying, setIsPlaying] = useState(true);
  const [exportProgress, setExportProgress] = useState<number | null>(null);
  const presentSchema = useProjectStore(state => state.present);

  const handleExport = async () => {
    try {
      setExportProgress(0);
      const uri = await exportProject(presentSchema, (prog) => {
        setExportProgress(prog);
      });
      Alert.alert(`Export Successful! Saved to ${uri}`);
    } catch (e: any) {
      Alert.alert(`Export Failed: ${e.message}`);
    } finally {
      setExportProgress(null);
    }
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (isPlaying) {
      // Loop from current time to end
      currentTime.value = withRepeat(
        withTiming(TIMELINE_MAX_SECONDS, { 
          duration: (TIMELINE_MAX_SECONDS - currentTime.value) * 1000, 
          easing: Easing.linear 
        }),
        -1,
        false
      );
    } else {
      cancelAnimation(currentTime);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isPlaying]);

  return (
    <GestureHandlerRootView style={styles.container}>
      <SafeAreaView style={styles.container}>
        <KeyboardAvoidingView 
          style={styles.keyboardView} 
          behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        >
          <View style={styles.header}>
            <Text style={styles.title}>Trimm Studio</Text>
            <View style={styles.buttonContainer}>
              <Button 
                title={isPlaying ? "Pause" : "Play"} 
                onPress={() => setIsPlaying(!isPlaying)} 
              />
              <TouchableOpacity style={styles.exportBtn} onPress={handleExport}>
                <Text style={styles.exportText}>Export</Text>
              </TouchableOpacity>
            </View>
          </View>
          
          <View style={styles.previewContainer}>
            <PreviewEngine currentTime={currentTime} isPlaying={isPlaying} />
          </View>

          <View style={styles.timelineContainer}>
            <Timeline 
              currentTime={currentTime} 
              onScrubStart={() => setIsPlaying(false)}
            />
            <AssetPicker />
          </View>
          
          <View style={styles.chatContainer}>
            <Chat />
          </View>
        </KeyboardAvoidingView>

        {exportProgress !== null && (
          <View style={styles.exportOverlay}>
            <View style={styles.exportBox}>
              <Text style={styles.exportTitle}>Exporting Video...</Text>
              <View style={styles.progressBarBg}>
                <View style={[styles.progressBarFill, { width: `${exportProgress * 100}%` }]} />
              </View>
              <Text style={styles.exportPercent}>{Math.round(exportProgress * 100)}%</Text>
            </View>
          </View>
        )}
      </SafeAreaView>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#000' },
  keyboardView: { flex: 1 },
  header: {
    padding: 12,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    borderBottomWidth: 1,
    borderBottomColor: '#222',
  },
  title: { color: '#FFF', fontSize: 16, fontWeight: 'bold' },
  exportBtn: {
    backgroundColor: '#E50914',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 4,
    marginLeft: 8,
  },
  exportText: {
    color: '#FFF',
    fontWeight: 'bold',
  },
  buttonContainer: {
    flexDirection: 'row', 
    alignItems: 'center'
  },
  previewContainer: { flex: 3, backgroundColor: '#111' },
  timelineContainer: { flex: 2, backgroundColor: '#1A1A1A' },
  chatContainer: { flex: 3, backgroundColor: '#222' },
  exportOverlay: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: 'rgba(0,0,0,0.8)',
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 999,
  },
  exportBox: {
    width: '80%',
    backgroundColor: '#2A2A2A',
    padding: 24,
    borderRadius: 12,
    alignItems: 'center',
  },
  exportTitle: {
    color: '#FFF',
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 16,
  },
  progressBarBg: {
    width: '100%',
    height: 12,
    backgroundColor: '#111',
    borderRadius: 6,
    overflow: 'hidden',
    marginBottom: 12,
  },
  progressBarFill: {
    height: '100%',
    backgroundColor: '#E50914',
  },
  exportPercent: {
    color: '#CCC',
    fontSize: 14,
  },
});

export default App;
