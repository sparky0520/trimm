import React from 'react';
import { View, Button, StyleSheet } from 'react-native';
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
      <Button title="Import Media" onPress={openPicker} color="#4CAF50" />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    padding: 8,
    backgroundColor: '#2A2A2A',
    borderTopWidth: 1,
    borderTopColor: '#333',
    flexDirection: 'row',
    justifyContent: 'center',
  },
});
