import React, { useEffect, useState } from 'react';
import { StyleSheet, View, Text, KeyboardAvoidingView, Platform, TouchableOpacity, Alert } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { useSharedValue, withTiming, Easing, cancelAnimation, withRepeat } from 'react-native-reanimated';
import { PreviewEngine } from './src/components/PreviewEngine';
import { Timeline } from './src/components/Timeline';
import { Chat } from './src/components/Chat';
import { AssetPicker } from './src/components/AssetPicker';
import { useProjectStore } from './src/store/projectStore';
import { exportProject } from './src/services/exportService';
import { NavigationContainer } from '@react-navigation/native';
import { createDrawerNavigator } from '@react-navigation/drawer';
import { ChatDrawerContent } from './src/components/ChatDrawerContent';
import { useAuthStore } from './src/store/authStore';
import { GoogleSignin } from '@react-native-google-signin/google-signin';

export const TIMELINE_MAX_SECONDS = 30;

const Drawer = createDrawerNavigator();

function TrimmStudioScreen({ navigation }: any): React.JSX.Element {
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

  useEffect(() => {
    if (isPlaying) {
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
  }, [isPlaying, currentTime]);

  return (
    <SafeAreaView style={styles.container}>
      <KeyboardAvoidingView 
        style={styles.keyboardView} 
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      >
        <View style={styles.header}>
          <View style={styles.buttonContainer}>
            <TouchableOpacity onPress={() => navigation.openDrawer()} style={{marginRight: 12}}>
              <Text style={styles.menuIcon}>☰</Text>
            </TouchableOpacity>
            <Text style={styles.title}>Trimm</Text>
          </View>
          <View style={styles.buttonContainer}>
            <TouchableOpacity 
              onPress={() => setIsPlaying(!isPlaying)}
              style={styles.playBtn}
            >
              <Text style={styles.playText}>{isPlaying ? "Pause" : "Play"}</Text>
            </TouchableOpacity>
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
  );
}

function App(): React.JSX.Element {
  const { initialize } = useAuthStore();

  useEffect(() => {
    initialize();
    GoogleSignin.configure({
      webClientId: '781659307868-b58g4qtacl6hjvdfs19nel896kbdomm8.apps.googleusercontent.com',
      iosClientId: '781659307868-19r9atulmptqncgqnsm08ttin94l850p.apps.googleusercontent.com',
    });
  }, [initialize]);

  return (
    <GestureHandlerRootView style={styles.wrapper}>
      <NavigationContainer>
        <Drawer.Navigator 
          drawerContent={ChatDrawerContent}
          screenOptions={{ headerShown: false, drawerStyle: { width: 300 } }}
        >
          <Drawer.Screen name="Studio" component={TrimmStudioScreen} />
        </Drawer.Navigator>
      </NavigationContainer>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  wrapper: { flex: 1, backgroundColor: '#09090B' },
  container: { flex: 1, backgroundColor: '#09090B' },
  keyboardView: { flex: 1 },
  header: {
    padding: 12,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: '#09090B',
  },
  menuIcon: { color: '#FAFAFA', fontSize: 24 },
  title: { color: '#FAFAFA', fontSize: 24, fontWeight: '600', letterSpacing: -1 },
  playBtn: {
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  playText: {
    color: '#FAFAFA',
    fontSize: 14,
  },
  exportBtn: {
    backgroundColor: '#FAFAFA',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
    marginLeft: 8,
  },
  exportText: {
    color: '#09090B',
    fontWeight: 'bold',
  },
  buttonContainer: {
    flexDirection: 'row', 
    alignItems: 'center'
  },
  previewContainer: { flex: 3, backgroundColor: '#18181B' },
  timelineContainer: { flex: 2, backgroundColor: '#09090B' },
  chatContainer: { flex: 3, backgroundColor: '#09090B' },
  exportOverlay: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: 'rgba(9,9,11,0.8)',
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 999,
  },
  exportBox: {
    width: '80%',
    backgroundColor: '#27272A',
    padding: 24,
    borderRadius: 12,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#3F3F46'
  },
  exportTitle: {
    color: '#FAFAFA',
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 16,
  },
  progressBarBg: {
    width: '100%',
    height: 12,
    backgroundColor: '#18181B',
    borderRadius: 6,
    overflow: 'hidden',
    marginBottom: 12,
  },
  progressBarFill: {
    height: '100%',
    backgroundColor: '#FAFAFA',
  },
  exportPercent: {
    color: '#D4D4D8',
    fontSize: 14,
  },
});

export default App;
