import { FFmpegKit, isSuccessReturnCode, Statistics } from 'ffmpeg-kit-extended';
import { CameraRoll } from '@react-native-camera-roll/camera-roll';
import RNFS from 'react-native-fs';
import { Image } from 'react-native';
import { Skia, ImageFormat } from '@shopify/react-native-skia';
import { ProjectSchema } from '../types/schema';
import { compileSchemaToCommand } from '../utils/ffmpegCompiler';
import { drawTimelineToCanvas } from '../utils/canvasRenderer';

export const exportProject = async (
  schema: ProjectSchema, 
  onProgress: (progress: number) => void
): Promise<string> => {
  return new Promise(async (resolve, reject) => {
    let sequenceDir = '';
    try {
      // 1. Setup UI Sequence generation
      const sessionId = Date.now();
      sequenceDir = `${RNFS.CachesDirectoryPath}/ui_seq_${sessionId}`;
      await RNFS.mkdir(sequenceDir);

      let width = 1920, height = 1080;
      if (schema.settings.aspectRatio === '9:16') { width = 1080; height = 1920; }
      else if (schema.settings.aspectRatio === '1:1') { width = 1080; height = 1080; }
      const fps = schema.settings.fps || 30;

      const durationStr = schema.timeline.length > 0 
        ? Math.max(...schema.timeline.map(n => n.end))
        : 1;
      const totalFrames = Math.ceil(durationStr * fps);

      // 2. Load Font for Text Nodes
      const fontAsset = Image.resolveAssetSource(require('../assets/fonts/Roboto.ttf'));
      if (!fontAsset) throw new Error("Could not resolve font asset");
      const fontData = await Skia.Data.fromURI(fontAsset.uri);
      const typeface = Skia.Typeface.MakeFreeTypeFaceFromData(fontData);
      if (!typeface) throw new Error("Could not load font");

      const surface = Skia.Surface.MakeOffscreen(width, height);
      if (!surface) throw new Error("Could not create Skia surface");
      const canvas = surface.getCanvas();

      // 3. Render each frame to PNG
      for (let f = 0; f < totalFrames; f++) {
        const time = f / fps;
        
        // Clear canvas and draw timeline
        drawTimelineToCanvas(canvas, schema, time, width, height, typeface);

        const image = surface.makeImageSnapshot();
        const pngData = image.encodeToBase64(ImageFormat.PNG, 100);
        const framePath = `${sequenceDir}/ui_frame_${String(f + 1).padStart(4, '0')}.png`;
        await RNFS.writeFile(framePath, pngData, 'base64');
        
        // Report progress for rendering phase (first 50%)
        onProgress((f / totalFrames) * 0.5);
      }

      // 4. Stitch with FFmpeg
      const outputPath = `${RNFS.CachesDirectoryPath}/export_${sessionId}.mp4`;
      const uiSequencePattern = `${sequenceDir}/ui_frame_%04d.png`;
      const command = compileSchemaToCommand(schema, uiSequencePattern, outputPath);

      const totalMilliseconds = durationStr * 1000;

      console.log('Executing FFmpeg command:', command);

      FFmpegKit.executeAsync(command, {
        completeCallback: async (session: any) => {
          const returnCode = await session.getReturnCode();
          if (isSuccessReturnCode(returnCode)) {
            console.log('Export succeeded!');
            try {
              const uri = await CameraRoll.save(outputPath, { type: 'video' });
              resolve(uri);
            } catch (saveErr) {
              reject(saveErr);
            } finally {
              RNFS.unlink(outputPath).catch(() => {});
              RNFS.unlink(sequenceDir).catch(() => {}); // cleanup sequence
            }
          } else {
            const logs = await session.getLogsAsString();
            console.error('Export failed\n', logs);
            RNFS.unlink(sequenceDir).catch(() => {}); // cleanup sequence
            reject(new Error('FFmpeg process failed'));
          }
        },
        logCallback: (_log: any) => {},
        statisticsCallback: (statistics: Statistics) => {
          const time = statistics.time ?? 0;
          if (time > 0) {
            // Second 50% of progress
            const progress = 0.5 + Math.min(time / totalMilliseconds, 1) * 0.5;
            onProgress(progress);
          }
        }
      });
    } catch (e) {
      if (sequenceDir) RNFS.unlink(sequenceDir).catch(() => {});
      reject(e);
    }
  });
};
