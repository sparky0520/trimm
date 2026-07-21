'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { ingestTrack, validateOptions } = require('../server/audio-ingestion.cjs');

const baseOptions = {
  id: 'calm-track', sourceUrl: 'https://www.youtube.com/watch?v=licensed', publicBaseUrl: 'https://cdn.example/audio',
  outputDir: '', licenseId: 'youtube-audio-library:licensed', rightsConfirmed: true, attributionRequired: true,
  attribution: 'Example Artist — Calm Track (CC BY 4.0)', moods: ['calm'], energy: 0.3,
};

test('ingestion requires explicit rights confirmation and attribution', () => {
  assert.throws(() => validateOptions({ ...baseOptions, rightsConfirmed: false }), /rights-confirmed/);
  assert.throws(() => validateOptions({ ...baseOptions, attribution: '' }), /Attribution text/);
  assert.throws(() => validateOptions({ ...baseOptions, sourceUrl: 'http://youtube.com/watch?v=x' }), /HTTPS/);
});

test('ingestion downloads, fingerprints, and atomically updates the public catalog', async (t) => {
  const outputDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'ctrlb-audio-'));
  t.after(() => fs.promises.rm(outputDir, { recursive: true, force: true }));
  const calls = [];
  const execFile = async (command, args) => {
    calls.push({ command, args });
    if (args.includes('--dump-single-json')) return { stdout: JSON.stringify({ title: 'Calm Track', uploader: 'Example Artist', webpage_url: baseOptions.sourceUrl }) };
    if (command === 'ffprobe') return { stdout: '12.345\n' };
    const template = args[args.indexOf('--output') + 1];
    const downloaded = template.replace('%(ext)s', 'mp3');
    await fs.promises.writeFile(downloaded, 'licensed audio bytes');
    return { stdout: `${downloaded}\n` };
  };
  const result = await ingestTrack({ ...baseOptions, outputDir }, { execFile });
  assert.equal(result.item.durationMs, 12345);
  assert.equal(result.item.title, 'Calm Track');
  assert.equal(result.item.previewUrl, 'https://cdn.example/audio/media/calm-track.mp3');
  assert.match(result.item.checksum, /^[a-f0-9]{64}$/);
  const catalog = JSON.parse(await fs.promises.readFile(path.join(outputDir, 'catalog.v1.json'), 'utf8'));
  assert.deepEqual(catalog.items, [result.item]);
  assert.equal(calls.filter((call) => call.command === 'yt-dlp').length, 2);
});

test('ingestion rejects a redirected non-YouTube source', async (t) => {
  const outputDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'ctrlb-audio-'));
  t.after(() => fs.promises.rm(outputDir, { recursive: true, force: true }));
  await assert.rejects(ingestTrack({ ...baseOptions, outputDir }, { execFile: async () => ({ stdout: JSON.stringify({ webpage_url: 'https://example.com/audio' }) }) }), /Only YouTube/);
});
