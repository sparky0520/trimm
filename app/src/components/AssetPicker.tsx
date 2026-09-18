import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { launchImageLibrary } from 'react-native-image-picker';
import { FFprobeKit } from 'ffmpeg-kit-extended';
import { useProjectStore } from '../store/projectStore';

export const AssetPicker = () => {
  const processToolCall = useProjectStore(state => state.processToolCall);

  const openPicker = async () => {
    const result = await launchImageLibrary({
      mediaType: 'mixed', // allow images and videos
      selectionLimit: 1,
    });

    if (result.assets && result.assets.length > 0) {
      const asset = result.assets[0];
      
      const isVideo = asset.type?.startsWith('video');
      
      let endDuration = 5;
      if (typeof asset.duration === 'number' && asset.duration > 0) {
        endDuration = asset.duration;
      } else if (isVideo && asset.uri) {
        try {
          const session = await FFprobeKit.getMediaInformation(asset.uri);
          const info = session.getMediaInformation();
          if (info && info.duration) {
            const parsed = parseFloat(info.duration);
            if (!isNaN(parsed)) {
              endDuration = parsed;
            }
          }
        } catch (e) {
          console.warn('Failed to get duration from FFprobeKit', e);
        }
      }
      
      processToolCall({
        tool: 'add_clip',
        type: isVideo ? 'video' : 'image',
        source: asset.uri || '',
        start: 0,
        end: endDuration,
        volume: isVideo ? 1 : undefined,
      });
    }
  };

  return (
    <View style={styles.container}>
      <TouchableOpacity style={styles.button} onPress={openPicker}>
        <Text style={styles.buttonText}>+ Import Media</Text>
      </TouchableOpacity>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    paddingVertical: 12,
    backgroundColor: 'transparent',
    flexDirection: 'row',
    justifyContent: 'center',
  },
  button: {
    backgroundColor: '#FAFAFA',
    paddingHorizontal: 20,
    paddingVertical: 12,
    borderRadius: 24,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 2,
  },
  buttonText: {
    color: '#09090B',
    fontSize: 15,
    fontWeight: '600',
  }
});
