import { ProjectSchema, TextNode, VideoNode, AudioNode } from '../types/schema';

export const compileSchemaToCommand = (schema: ProjectSchema, outputPath: string): string => {
  const inputs: string[] = [];
  const filters: string[] = [];
  
  const videos = schema.timeline.filter(n => n.type === 'video') as VideoNode[];
  const audios = schema.timeline.filter(n => n.type === 'audio') as AudioNode[];
  const texts = schema.timeline.filter(n => n.type === 'text') as TextNode[];

  // 1. Inputs
  videos.forEach(v => inputs.push(`-i "${v.src}"`));
  audios.forEach(a => inputs.push(`-i "${a.src}"`));
  
  // Create a base black background using color filter
  let width = 1920, height = 1080;
  if (schema.settings.aspectRatio === '9:16') { width = 1080; height = 1920; }
  else if (schema.settings.aspectRatio === '1:1') { width = 1080; height = 1080; }
  const fps = schema.settings.fps || 30;
  const bgColor = (schema.settings.backgroundColor || '#000000').replace('#', '0x');
  
  inputs.unshift(`-f lavfi -i color=c=${bgColor}:s=${width}x${height}:r=${fps}`);
  
  let currentVideoOut = '[0:v]';
  let videoIndex = 1; // 0 is color background

  // 2. Video processing (trim and overlay on base)
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

  let currentOut = currentVideoOut;

  // 3. Text Processing (complex animations)
  texts.forEach((t, i) => {
    const fontfile = t.fontFamily?.startsWith('http') ? t.fontFamily : 'Roboto'; 
    const fontSize = t.fontSize || 60;
    const fontColor = t.color || 'white';
    
    // Default center positioning
    let xExpr = `(w-text_w)/2`;
    let yExpr = `(h-text_h)/2`;
    let alphaExpr = `1`;

    // Complex Animations
    if (t.animation) {
      if (t.animation === 'fade_in') {
        alphaExpr = `if(lt(t,${t.start + 1}), (t-${t.start}), 1)`;
      } else if (t.animation === 'fade_out') {
        alphaExpr = `if(gt(t,${t.end - 1}), (${t.end}-t), 1)`;
      } else if (t.animation === 'slide_in') {
        // Slide from left over 1 second
        xExpr = `if(lt(t,${t.start + 1}), (t-${t.start}-1)*w + (w-text_w)/2, (w-text_w)/2)`;
      } else if (t.animation === 'slide_out') {
        // Slide to right over 1 second
        xExpr = `if(gt(t,${t.end - 1}), (t-${t.end - 1})*w + (w-text_w)/2, (w-text_w)/2)`;
      }
    }

    // Handle Transform (static overrides)
    if (t.transform) {
      if (t.transform.x !== undefined) xExpr = `${t.transform.x}`;
      if (t.transform.y !== undefined) yExpr = `${t.transform.y}`;
      // Note: FFmpeg drawtext does not easily support rotation or scale without drawing onto a separate transparent stream and rotating that.
      // We accept this degradation for MVP.
    }

    const nextOut = `[out_t${i}]`;
    const drawtext = `drawtext=text='${t.content}':fontfile='${fontfile}':fontsize=${fontSize}:fontcolor=${fontColor}:x='${xExpr}':y='${yExpr}':alpha='${alphaExpr}':enable='between(t,${t.start},${t.end})'`;
    filters.push(`${currentOut}${drawtext}${nextOut}`);
    currentOut = nextOut;
  });

  let audioIndex = videoIndex;
  const audioMixStreams: string[] = [];
  
  // 4. Audio Processing (videos have audio, plus standalone audio)
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

  const duration = Math.max(...schema.timeline.map(n => n.end));

  const filterComplex = `-filter_complex "${filters.join(';')}"`;
  const mapV = `-map "${currentOut}"`;
  const mapA = audioMixStreams.length > 0 ? `-map "[aout]"` : '';

  return `${inputs.join(' ')} ${filterComplex} ${mapV} ${mapA} -t ${duration} -c:v mpeg4 -c:a aac -y "${outputPath}"`;
};
