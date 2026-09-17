import React from 'react';
import { View, Button, StyleSheet } from 'react-native';
import { launchImageLibrary } from 'react-native-image-picker';
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
      
      // We assume every imported clip starts at 0 and ends at 5s for this basic implementation.
      // In a real app, we'd read the actual video duration.
      processToolCall({
        tool: 'add_clip',
        type: isVideo ? 'video' : 'image',
        source: asset.uri || '',
        start: 0,
        end: 5,
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
