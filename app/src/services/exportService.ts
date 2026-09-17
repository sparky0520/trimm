import { FFmpegKit, ReturnCode, Statistics } from 'ffmpeg-kit-react-native';
import { CameraRoll } from '@react-native-camera-roll/camera-roll';
import RNFS from 'react-native-fs';
import { ProjectSchema } from '../types/schema';
import { compileSchemaToCommand } from '../utils/ffmpegCompiler';

export const exportProject = async (
  schema: ProjectSchema, 
  onProgress: (progress: number) => void
): Promise<string> => {
  return new Promise(async (resolve, reject) => {
    try {
      const outputPath = `${RNFS.CachesDirectoryPath}/export_${Date.now()}.mp4`;
      const command = compileSchemaToCommand(schema, outputPath);

      // Determine total duration for progress calculation
      const durationStr = schema.timeline.length > 0 
        ? Math.max(...schema.timeline.map(n => n.end))
        : 1;

      const totalMilliseconds = durationStr * 1000;

      console.log('Executing FFmpeg command:', command);

      FFmpegKit.executeAsync(
        command,
        async (session) => {
          const returnCode = await session.getReturnCode();
          if (ReturnCode.isSuccess(returnCode)) {
            console.log('Export succeeded!');
            // Save to gallery
            try {
              const uri = await CameraRoll.save(outputPath, { type: 'video' });
              resolve(uri);
            } catch (saveErr) {
              console.error('Failed to save to Camera Roll', saveErr);
              reject(saveErr);
            } finally {
              // Clean up temp file
              RNFS.unlink(outputPath).catch(e => console.log('Cleanup error', e));
            }
          } else {
            const logs = await session.getLogsAsString();
            console.error('Export failed\n', logs);
            reject(new Error('FFmpeg process failed'));
          }
        },
        (log) => {
          // console.log(log.getMessage());
        },
        (statistics: Statistics) => {
          const time = statistics.getTime();
          if (time > 0) {
            const progress = Math.min(time / totalMilliseconds, 1);
            onProgress(progress);
          }
        }
      );
    } catch (e) {
      reject(e);
    }
  });
};
