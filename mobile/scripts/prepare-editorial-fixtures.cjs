const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const mobileRoot = path.resolve(__dirname, '..');
const manifestPath = path.join(
  mobileRoot,
  'app/src/test/resources/editorial/mobile-editorial-fixtures.v1.json'
);
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const outputDir = path.join(mobileRoot, manifest.redistribution.outputDirectory);
const sourceDir = path.join(outputDir, 'sources');

function run(program, args, errorMessage) {
  const result = spawnSync(program, args, { stdio: 'inherit' });
  if (result.error?.code === 'ENOENT') throw new Error(`${program} is required`);
  if (result.status !== 0) throw new Error(`${errorMessage} (exit ${result.status})`);
}

function download(source, destination) {
  if (!fs.existsSync(destination)) {
    const partial = `${destination}.partial`;
    run('curl', [
      '--fail', '--location', '--retry', '4', '--retry-all-errors',
      '--user-agent', 'trimm-mobile-editorial-fixture/1.0',
      '--output', partial, source.sourceUrl
    ], `Download failed for ${source.id}`);
    fs.renameSync(partial, destination);
  }
  const actual = crypto.createHash('sha256').update(fs.readFileSync(destination)).digest('hex');
  if (actual !== source.sha256) {
    throw new Error(`Integrity check failed for ${source.id}: expected ${source.sha256}, received ${actual}`);
  }
}

try {
  fs.mkdirSync(sourceDir, { recursive: true });
  const sourceFiles = new Map();
  for (const source of manifest.sources) {
    const file = path.join(sourceDir, `${source.id}.webm`);
    download(source, file);
    sourceFiles.set(source.id, file);
  }
  for (const clip of manifest.clips) {
    const [startMs, endMs] = clip.sourceRangeMs;
    const output = path.join(outputDir, `${clip.id}.mp4`);
    run('ffmpeg', [
      '-hide_banner', '-loglevel', 'error', '-y',
      '-ss', String(startMs / 1000),
      '-t', String((endMs - startMs) / 1000),
      '-i', sourceFiles.get(clip.sourceId),
      '-vf', 'scale=720:1280:force_original_aspect_ratio=decrease,pad=720:1280:(ow-iw)/2:(oh-ih)/2,setsar=1',
      '-r', '30', '-c:v', 'libx264', '-preset', 'fast', '-crf', '25', '-pix_fmt', 'yuv420p',
      '-c:a', 'aac', '-b:a', '96k', '-ar', '48000',
      '-movflags', '+faststart', output
    ], `Failed to prepare ${clip.id}`);
  }
  fs.writeFileSync(
    path.join(outputDir, 'READY'),
    `${manifest.fixtureVersion}\nPrepared derivatives require the manifest attribution and source licenses.\n`
  );
  console.log(`Prepared ${manifest.clips.length} mobile editorial clips in ${outputDir}`);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
