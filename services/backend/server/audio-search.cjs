'use strict';

const { FreesoundProvider, YouTubeAudioLibraryProvider } = require('./audio-providers.cjs');

const ALLOWED_KINDS = new Set(['MUSIC', 'SOUND_EFFECT']);

function normalizedKind(value) {
  const kind = String(value || '').trim().toUpperCase();
  if (!ALLOWED_KINDS.has(kind)) throw Object.assign(new Error('Unsupported audio search kind.'), { status: 400, code: 'invalid_audio_kind' });
  return kind;
}

function terms(value) {
  return String(value || '').toLocaleLowerCase().split(/[^\p{L}\p{N}]+/u).filter((item) => item.length > 1).slice(0, 12);
}

function isRenderable(item, kind) {
  return item && String(item.kind).toUpperCase() === kind && item.status === 'ready' &&
    Array.isArray(item.quarantineReasons) && item.quarantineReasons.length === 0 &&
    Array.isArray(item.allowedUse) && item.allowedUse.includes('rendered-video') &&
    typeof item.id === 'string' && /^[a-zA-Z0-9._-]{2,80}$/.test(item.id) &&
    typeof item.durationMs === 'number' && item.durationMs > 0 &&
    typeof item.checksum === 'string' && /^[a-fA-F0-9]{64}$/.test(item.checksum) &&
    typeof item.downloadUrl === 'string' && item.downloadUrl.startsWith('https://') &&
    (String(item.licenseId || '').trim() || String(item.licenseText || '').trim()) &&
    (!item.attributionRequired || String(item.attribution || '').trim());
}

class AudioSearchService {
  constructor({ catalogUrl, freesoundApiKey, fetch = globalThis.fetch, cacheMs = 5 * 60 * 1000, now = Date.now } = {}) {
    this.catalogUrl = String(catalogUrl || '');
    this.fetch = fetch;
    this.cacheMs = cacheMs;
    this.now = now;
    this.cached = null;
    this.freesound = freesoundApiKey ? new FreesoundProvider({ apiKey: freesoundApiKey, fetch }) : null;
    this.youtube = new YouTubeAudioLibraryProvider();
  }

  async catalog() {
    if (!this.catalogUrl.startsWith('https://')) {
      return [];
    }
    if (this.cached && this.cached.expiresAt > this.now()) return this.cached.items;
    const response = await this.fetch(this.catalogUrl, { headers: { Accept: 'application/json' } });
    if (!response.ok) throw Object.assign(new Error(`Audio registry request failed (${response.status}).`), { status: 503, code: 'audio_registry_unavailable' });
    const document = await response.json();
    if (document?.version !== 1 || !Array.isArray(document.items) || document.items.length > 10_000) {
      throw Object.assign(new Error('The governed audio registry returned an invalid document.'), { status: 503, code: 'invalid_audio_registry' });
    }
    this.cached = { expiresAt: this.now() + this.cacheMs, items: document.items };
    return document.items;
  }

  async search({ kind, query, minimumDurationMs = 0, maximumDurationMs = 600_000, maximumResults = 5 }) {
    kind = normalizedKind(kind);
    const queryTerms = terms(query);
    if (!queryTerms.length || !Number.isSafeInteger(minimumDurationMs) || !Number.isSafeInteger(maximumDurationMs) ||
        minimumDurationMs < 0 || maximumDurationMs <= minimumDurationMs || maximumDurationMs > 60 * 60 * 1000 ||
        !Number.isSafeInteger(maximumResults) || maximumResults < 1 || maximumResults > 8) {
      throw Object.assign(new Error('Invalid bounded audio search.'), { status: 400, code: 'invalid_audio_search' });
    }
    const items = await this.catalog();
    const registryAssets = items.filter((item) => isRenderable(item, kind) && item.durationMs >= minimumDurationMs && item.durationMs <= maximumDurationMs)
      .map((item) => {
        const searchable = [item.title, item.artist, ...(item.tags || []), ...(item.moods || [])].join(' ').toLocaleLowerCase();
        const score = queryTerms.reduce((total, term) => total + (searchable.includes(term) ? 1 : 0), 0);
        return { item, score };
      }).filter(({ score }) => score > 0)
      .sort((a, b) => b.score - a.score || a.item.durationMs - b.item.durationMs || a.item.id.localeCompare(b.item.id))
      .slice(0, maximumResults).map(({ item }) => ({
        assetId: item.id, kind, title: String(item.title || item.id).slice(0, 200),
        artist: String(item.artist || '').slice(0, 120), durationMs: Math.round(item.durationMs),
        tags: [...new Set([...(item.tags || []), ...(item.moods || [])].map(String))].slice(0, 20),
        provider: String(item.provider || '').slice(0, 80), previewUrl: item.previewUrl || null,
        license: { id: String(item.licenseId || '').slice(0, 200), attributionRequired: Boolean(item.attributionRequired) },
        renderable: true, availability: 'READY',
      }));
    let freesoundAssets = [];
    let freesoundStatus = this.freesound ? 'available' : 'configuration_required';
    if (this.freesound && registryAssets.length < maximumResults) {
      try {
        freesoundAssets = (await this.freesound.search(query, { pageSize: maximumResults }))
          .filter((item) => item.durationMs >= minimumDurationMs && item.durationMs <= maximumDurationMs)
          .slice(0, maximumResults - registryAssets.length).map((item) => ({
            assetId: `freesound-${item.providerAssetId}`, kind, title: String(item.title).slice(0, 200),
            artist: String(item.artist || '').slice(0, 120), durationMs: item.durationMs, tags: [],
            provider: 'freesound', previewUrl: item.previewUrl,
            license: { id: String(item.licenseId || '').slice(0, 200), attributionRequired: true },
            renderable: false, availability: 'FREESOUND_AUTHORIZATION_REQUIRED',
          }));
      } catch (error) {
        freesoundStatus = error.code || 'provider_unavailable';
      }
    }
    const assisted = this.youtube.search(query);
    return {
      assets: [...registryAssets, ...freesoundAssets].slice(0, maximumResults),
      sources: [
        { provider: 'governed-registry', status: this.catalogUrl.startsWith('https://') ? 'available' : 'configuration_required' },
        { provider: 'freesound', status: freesoundStatus },
        { provider: 'youtube-audio-library', status: 'assisted', url: assisted.url, message: assisted.message },
      ],
    };
  }
}

module.exports = { AudioSearchService, isRenderable };
