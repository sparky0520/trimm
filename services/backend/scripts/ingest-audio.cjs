'use strict';

const path = require('node:path');
const { ingestTrack } = require('../server/audio-ingestion.cjs');

function parse(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith('--')) throw new Error(`Unexpected argument: ${key}`);
    if (key === '--rights-confirmed' || key === '--attribution-required') values[key.slice(2)] = true;
    else values[key.slice(2)] = argv[++index];
  }
  return values;
}

async function main() {
  const args = parse(process.argv.slice(2));
  const result = await ingestTrack({
    id: args.id, sourceUrl: args.url, title: args.title, artist: args.artist,
    licenseId: args.license, rightsConfirmed: args['rights-confirmed'],
    attributionRequired: args['attribution-required'], attribution: args.attribution,
    bpm: args.bpm, energy: args.energy, moods: String(args.moods || '').split(',').map((value) => value.trim().toLowerCase()).filter(Boolean),
    outputDir: path.resolve(args.output || 'public/audio'), publicBaseUrl: args['public-base-url'] || process.env.CTRLB_AUDIO_PUBLIC_BASE_URL,
    ytDlpPath: args['yt-dlp'], ffprobePath: args.ffprobe,
  });
  process.stdout.write(`${JSON.stringify(result.item, null, 2)}\n`);
}

main().catch((error) => { process.stderr.write(`Audio ingestion failed: ${error.message}\n`); process.exitCode = 1; });
