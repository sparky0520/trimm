'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { ingestTrack, validateOptions } = require('../server/audio-ingestion.cjs');

const base = {
  id: 'calm-track', filePath: 'downloaded.mp3', provider: 'youtube-audio-library',
  providerAssetId: 'library-track-123', sourceUrl: 'https://studio.youtube.com/channel/UC/music',
  licenseId: 'youtube-audio-library-standard', rightsConfirmed: true,
  allowedUse: ['commercial', 'rendered-video'], durationMs: 12345,
};

test('assisted ingestion requires local media, provenance, rights, and render scope', () => {
  assert.throws(() => validateOptions({ ...base, filePath: '' }), /filePath/);
  assert.throws(() => validateOptions({ ...base, rightsConfirmed: false }), /rights-confirmed/);
  assert.throws(() => validateOptions({ ...base, providerAssetId: '' }), /providerAssetId/);
  assert.throws(() => validateOptions({ ...base, allowedUse: ['personal'] }), /rendered-video/);
});

test('YouTube Audio Library ingestion imports a user-downloaded file without scraping', async (t) => {
  const dir = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'ctrlb-assisted-'));
  t.after(() => fs.promises.rm(dir, { recursive: true, force: true }));
  const filePath = path.join(dir, 'downloaded.mp3');
  await fs.promises.writeFile(filePath, 'download from provider control');
  const result = await ingestTrack({ ...base, filePath });
  assert.equal(result.item.provider, 'youtube-audio-library');
  assert.equal(result.item.status, 'ready');
  assert.equal(result.catalogPath, null);
  assert.equal(result.item.userScoped, true);
});

test('user-scoped provider media is not published without redistribution rights', async (t) => {
  const dir = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'ctrlb-no-publish-'));
  t.after(() => fs.promises.rm(dir, { recursive: true, force: true }));
  const filePath = path.join(dir, 'downloaded.mp3');
  await fs.promises.writeFile(filePath, 'bytes');
  await assert.rejects(ingestTrack({ ...base, filePath, outputDir: path.join(dir, 'public') }), /user cache/);
  assert.throws(() => validateOptions({ ...base, filePath, outputDir: dir, publish: true }), /redistribution rights/);
});
