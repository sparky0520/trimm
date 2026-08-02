'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { AudioSearchService } = require('../server/audio-search.cjs');

const checksum = 'a'.repeat(64);
const item = (overrides = {}) => ({
  id: 'soft-click', kind: 'SOUND_EFFECT', title: 'Soft keyboard click', artist: 'Fixture',
  durationMs: 420, tags: ['keyboard', 'clean'], moods: [], provider: 'fixture',
  licenseId: 'CC0', attributionRequired: false, allowedUse: ['rendered-video'],
  checksum, downloadUrl: 'https://assets.example/soft-click.wav', previewUrl: 'https://assets.example/preview.mp3',
  status: 'ready', quarantineReasons: [], ...overrides,
});

test('audio search returns only bounded renderable matching assets', async () => {
  const service = new AudioSearchService({ catalogUrl: 'https://catalog.example/v1.json', fetch: async () => ({
    ok: true, json: async () => ({ version: 1, items: [item(), item({ id: 'music', kind: 'MUSIC' }),
      item({ id: 'quarantined', status: 'quarantined', quarantineReasons: ['revoked'] })] }),
  }) });
  const result = await service.search({ kind: 'SOUND_EFFECT', query: 'keyboard click', minimumDurationMs: 100,
    maximumDurationMs: 1_000, maximumResults: 5 });
  assert.deepEqual(result.assets.map((asset) => asset.assetId), ['soft-click']);
  assert.equal(result.assets[0].license.id, 'CC0');
  assert.equal(result.assets[0].renderable, true);
  assert.equal('downloadUrl' in result.assets[0], false);
  assert.equal(result.sources.find((source) => source.provider === 'youtube-audio-library').status, 'assisted');
});

test('default search still exposes assisted providers without a governed registry', async () => {
  const service = new AudioSearchService();
  const result = await service.search({ kind: 'MUSIC', query: 'calm ambient', minimumDurationMs: 1,
    maximumDurationMs: 60_000, maximumResults: 3 });
  assert.deepEqual(result.assets, []);
  assert.equal(result.sources.find((source) => source.provider === 'freesound').status, 'configuration_required');
  assert.equal(result.sources.find((source) => source.provider === 'youtube-audio-library').status, 'assisted');
});

test('Freesound is searched by default when its API key is configured but discoveries are not directly placeable', async () => {
  const service = new AudioSearchService({ freesoundApiKey: 'fixture-key', fetch: async () => ({
    ok: true, json: async () => ({ results: [{ id: 42, name: 'Soft click', username: 'Artist',
      url: 'https://freesound.org/s/42', license: 'https://creativecommons.org/publicdomain/zero/1.0/',
      duration: .4, previews: { 'preview-hq-mp3': 'https://cdn.example/42.mp3' } }] }),
  }) });
  const result = await service.search({ kind: 'SOUND_EFFECT', query: 'soft click', minimumDurationMs: 100,
    maximumDurationMs: 1_000, maximumResults: 3 });
  assert.equal(result.assets[0].provider, 'freesound');
  assert.equal(result.assets[0].renderable, false);
  assert.equal(result.assets[0].availability, 'FREESOUND_AUTHORIZATION_REQUIRED');
  assert.equal(result.sources.find((source) => source.provider === 'youtube-audio-library').status, 'assisted');
});
