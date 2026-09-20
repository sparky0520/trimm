import { Platform } from 'react-native';
import { ProjectSchema, VideoNode, AudioNode } from '../types/schema';

export const compileSchemaToCommand = (schema: ProjectSchema, uiSequencePattern: string, outputPath: string): string => {
  const inputs: string[] = [];
  const filters: string[] = [];
  
  const videos = schema.timeline.filter(n => n.type === 'video') as VideoNode[];
  const audios = schema.timeline.filter(n => n.type === 'audio') as AudioNode[];

  // 1. Inputs
  videos.forEach(v => inputs.push(`-i "${v.src}"`));
  audios.forEach(a => inputs.push(`-i "${a.src}"`));
  
  const fps = schema.settings.fps || 30;
  
  // Add UI sequence as the last input
  inputs.push(`-framerate ${fps} -i "${uiSequencePattern}"`);
  const uiStreamIndex = videos.length + audios.length + 1; // +1 because color base is index 0

  // Create a base black background using color filter
  let width = 1920, height = 1080;
  if (schema.settings.aspectRatio === '9:16') { width = 1080; height = 1920; }
  else if (schema.settings.aspectRatio === '1:1') { width = 1080; height = 1080; }
  const bgColor = (schema.settings.backgroundColor || '#000000').replace('#', '0x');
  
  inputs.unshift(`-f lavfi -i color=c=${bgColor}:s=${width}x${height}:r=${fps}`);
  
  let currentVideoOut = '[0:v]';
  let videoIndex = 1; // 0 is color background

  // 2. Video processing (trim, scale, and overlay on base)
  videos.forEach((v, i) => {
    const vStream = `[${videoIndex}:v]`;
    const vTrimmed = `[v${i}_trim]`;
    // Scale to fit, trim, and set PTS
    filters.push(`${vStream}scale=${width}:${height}:force_original_aspect_ratio=decrease,pad=${width}:${height}:(ow-iw)/2:(oh-ih)/2,trim=start=0:end=${v.end - v.start},setpts=PTS-STARTPTS+${v.start}/TB${vTrimmed}`);
    
    const nextOut = `[out_v${i}]`;
    // Overlay it onto the main canvas, enabled only during its duration
    filters.push(`${currentVideoOut}${vTrimmed}overlay=enable='between(t,${v.start},${v.end})':x=0:y=0${nextOut}`);
    currentVideoOut = nextOut;
    videoIndex++;
  });

  // 3. Overlay the transparent UI sequence
  const uiStream = `[${uiStreamIndex}:v]`;
  const finalVideoOut = `[final_v]`;
  filters.push(`${currentVideoOut}${uiStream}overlay=x=0:y=0${finalVideoOut}`);

  let audioIndex = videoIndex;
  const audioMixStreams: string[] = [];
  
  // 4. Audio Processing
  videos.forEach((v, i) => {
    const aStream = `[${i+1}:a]`;
    const aTrimmed = `[a_v${i}_trim]`;
    filters.push(`${aStream}atrim=start=0:end=${v.end - v.start},asetpts=PTS-STARTPTS,adelay=${v.start * 1000}|${v.start * 1000}${aTrimmed}`);
    audioMixStreams.push(aTrimmed);
  });

  audios.forEach((a, i) => {
    const aStream = `[${audioIndex}:a]`;
    const aTrimmed = `[a_a${i}_trim]`;
    filters.push(`${aStream}atrim=start=0:end=${a.end - a.start},asetpts=PTS-STARTPTS,adelay=${a.start * 1000}|${a.start * 1000}${aTrimmed}`);
    audioMixStreams.push(aTrimmed);
    audioIndex++;
  });

  if (audioMixStreams.length > 0) {
    const amix = `${audioMixStreams.join('')}amix=inputs=${audioMixStreams.length}:duration=first:dropout_transition=3[aout]`;
    filters.push(amix);
  }

  const duration = schema.timeline.length > 0 ? Math.max(...schema.timeline.map(n => n.end)) : 1;

  const filterComplex = `-filter_complex "${filters.join(';')}"`;
  const mapV = `-map "${finalVideoOut}"`;
  const mapA = audioMixStreams.length > 0 ? `-map "[aout]"` : '';

  // Use hardware encoding if available
  let vcodec = 'libx264';
  if (Platform.OS === 'ios') vcodec = 'h264_videotoolbox';
  else if (Platform.OS === 'android') vcodec = 'h264_mediacodec';

  return `${inputs.join(' ')} ${filterComplex} ${mapV} ${mapA} -t ${duration} -c:v ${vcodec} -c:a aac -y "${outputPath}"`;
};
