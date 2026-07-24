'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {
  FreesoundProvider, LocalFileProvider, ProviderError, YouTubeAudioLibraryProvider,
} = require('../server/audio-providers.cjs');
const {
  assertRenderable, auditAsset, importUserFile, normalizeAsset, publicCatalogItem, verifyAsset,
} = require('../server/audio-rights.cjs');

function response(status, body, headers = {}) {
  return {
    status, ok: status >= 200 && status < 300, body,
    headers: { get: (name) => headers[name.toLowerCase()] || null },
    json: async () => body,
  };
}

const validAsset = {
  id: 'door-close', kind: 'SOUND_EFFECT', title: 'Door close', artist: 'Artist',
  provider: 'freesound', providerAssetId: '42', sourceUrl: 'https://freesound.org/s/42/',
  acquiredAt: '2026-07-24T00:00:00.000Z', checksum: 'a'.repeat(64),
  licenseId: 'CC0-1.0', attribution: 'Door close by Artist', attributionRequired: true,
  allowedUse: ['commercial', 'rendered-video'], rightsAttested: true, userScoped: true,
};

test('provider contract separates search, preview, acquisition, license, attribution, and revocation', () => {
  for (const provider of [
    new FreesoundProvider({ apiKey: 'server-secret', fetch: async () => response(200, { results: [] }) }),
    new YouTubeAudioLibraryProvider(), new LocalFileProvider(),
  ]) {
    for (const method of ['search', 'preview', 'acquire', 'captureLicense', 'attribution', 'revocation']) {
      assert.equal(typeof provider[method], 'function', `${provider.id}.${method}`);
    }
  }
});

test('Freesound search uses documented API and OAuth is mandatory for original download', async () => {
  const calls = [];
  const provider = new FreesoundProvider({ apiKey: 'server-secret', fetch: async (url, options) => {
    calls.push({ url: String(url), options });
    if (String(url).includes('/search/text/')) return response(200, { results: [{
      id: 42, name: 'Door', username: 'Artist', url: 'https://freesound.org/s/42/',
      license: 'CC0', previews: { 'preview-hq-mp3': 'https://cdn.freesound.org/p.mp3' }, duration: 1.25,
    }] });
    return response(200, { stream: true }, { 'content-type': 'audio/wav' });
  } });
  const results = await provider.search('door close');
  assert.equal(results[0].providerAssetId, '42');
  assert.match(calls[0].url, /^https:\/\/freesound\.org\/apiv2\/search\/text\//);
  assert.match(calls[0].url, /fields=/);
  await assert.rejects(provider.acquire('42'), (error) => error.code === 'AUTH_REQUIRED');
  await provider.acquire('42', { accessToken: 'user-oauth' });
  assert.equal(calls[1].options.headers.Authorization, 'Bearer user-oauth');
  assert.match(calls[1].url, /\/sounds\/42\/download\/$/);
});

test('provider failures, rate limits, and expired OAuth have stable errors', async () => {
  for (const [status, code] of [[500, 'PROVIDER_FAILURE'], [429, 'RATE_LIMITED'], [401, 'AUTH_EXPIRED']]) {
    const provider = new FreesoundProvider({ apiKey: 'key', fetch: async () => response(status, {}) });
    await assert.rejects(provider.search('impact'), (error) => error instanceof ProviderError && error.code === code);
  }
  const provider = new FreesoundProvider({ apiKey: 'key', fetch: async () => response(401, {}) });
  await assert.rejects(provider.acquire('1', { accessToken: 'expired' }), (error) => error.code === 'AUTH_EXPIRED');
});

test('YouTube Audio Library only supports user-initiated assisted import', () => {
  const provider = new YouTubeAudioLibraryProvider();
  assert.equal(provider.search().assisted, true);
  assert.match(provider.search().url, /^https:\/\/studio\.youtube\.com\//);
  assert.throws(() => provider.acquire('ordinary-youtube-video'), (error) => error.code === 'ASSISTED_IMPORT_REQUIRED');
});

test('local import requires attestation and preserves optional receipt artifact', async (t) => {
  const dir = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'ctrlb-rights-'));
  t.after(() => fs.promises.rm(dir, { recursive: true, force: true }));
  const media = path.join(dir, 'owned.wav');
  await fs.promises.writeFile(media, 'owned audio');
  await assert.rejects(importUserFile(media, { id: 'owned-audio' }), /rights attestation/i);
  const asset = await importUserFile(media, {
    id: 'owned-audio', rightsAttested: true, licenseId: 'owned-master',
    allowedUse: ['commercial', 'rendered-video'], rightsArtifact: { type: 'receipt', path: 'receipt.pdf' },
  });
  assert.equal(asset.status, 'ready');
  assert.equal(asset.provider, 'local-file');
  assert.deepEqual(asset.rightsArtifact, { type: 'receipt', path: 'receipt.pdf' });
  assert.match(asset.checksum, /^[a-f0-9]{64}$/);
});

test('unclear rights, license changes, revocation, and invalid checksum quarantine assets', async () => {
  assert.equal(normalizeAsset({ ...validAsset, licenseId: '', licenseText: null }).status, 'quarantined');
  assert.equal(normalizeAsset({ ...validAsset, checksum: 'bad' }).status, 'quarantined');
  const provider = new FreesoundProvider({ apiKey: 'key', fetch: async () => response(200, {}) });
  const changed = await auditAsset(validAsset, provider, { licenseId: 'CC-BY-4.0' });
  assert.equal(changed.status, 'quarantined');
  assert.ok(changed.quarantineReasons.includes('license-changed'));
  assert.throws(() => assertRenderable(changed), /quarantined/);
});

test('cached files work offline only while checksum and rights remain valid', async (t) => {
  const dir = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'ctrlb-cache-'));
  t.after(() => fs.promises.rm(dir, { recursive: true, force: true }));
  const media = path.join(dir, 'effect.wav');
  await fs.promises.writeFile(media, 'bytes');
  const imported = await importUserFile(media, {
    ...validAsset, id: 'offline-effect', provider: 'freesound', providerAssetId: '42',
  });
  assert.equal((await verifyAsset(imported)).status, 'ready');
  await fs.promises.writeFile(media, 'changed bytes');
  const invalid = await verifyAsset(imported);
  assert.equal(invalid.status, 'quarantined');
  assert.ok(invalid.quarantineReasons.includes('checksum-mismatch'));
});

test('provider originals cannot be republished without explicit redistribution rights', () => {
  assert.throws(() => publicCatalogItem(validAsset), /redistribution rights/);
  assert.equal(publicCatalogItem({ ...validAsset, allowedUse: [...validAsset.allowedUse, 'redistribution'] }).id, validAsset.id);
});
