'use strict';

const FREESOUND_API = 'https://freesound.org/apiv2';
const YOUTUBE_AUDIO_LIBRARY_URL = 'https://studio.youtube.com/channel/UC/music';

class ProviderError extends Error {
  constructor(message, code, details = {}) {
    super(message);
    this.name = 'ProviderError';
    this.code = code;
    this.details = details;
  }
}

async function providerJson(fetch, url, options = {}) {
  const response = await fetch(url, options);
  if (response.status === 401) throw new ProviderError('Provider authorization expired.', 'AUTH_EXPIRED');
  if (response.status === 429) {
    throw new ProviderError('Provider rate limit reached.', 'RATE_LIMITED', {
      retryAfter: response.headers?.get?.('retry-after') || null,
    });
  }
  if (!response.ok) throw new ProviderError(`Provider request failed (${response.status}).`, 'PROVIDER_FAILURE');
  return response.json();
}

class FreesoundProvider {
  constructor({ apiKey, fetch = globalThis.fetch, apiBase = FREESOUND_API } = {}) {
    if (!apiKey) throw new Error('Freesound apiKey is required.');
    this.id = 'freesound';
    this.fetch = fetch;
    this.apiKey = apiKey;
    this.apiBase = apiBase.replace(/\/$/, '');
  }

  async search(query, { page = 1, pageSize = 20 } = {}) {
    const url = new URL(`${this.apiBase}/search/text/`);
    url.searchParams.set('query', query);
    url.searchParams.set('page', String(page));
    url.searchParams.set('page_size', String(Math.min(50, Math.max(1, pageSize))));
    url.searchParams.set('token', this.apiKey);
    url.searchParams.set('fields', 'id,name,username,url,license,previews,duration');
    const body = await providerJson(this.fetch, url);
    return (body.results || []).map((sound) => ({
      provider: this.id, providerAssetId: String(sound.id), title: sound.name,
      artist: sound.username, sourceUrl: sound.url, licenseId: sound.license || '',
      durationMs: Math.round(Number(sound.duration || 0) * 1000),
      previewUrl: sound.previews?.['preview-hq-mp3'] || sound.previews?.['preview-lq-mp3'] || null,
    }));
  }

  preview(asset) {
    return { url: asset.previewUrl, cacheable: false, purpose: 'preview-only' };
  }

  async acquire(providerAssetId, { accessToken } = {}) {
    if (!accessToken) throw new ProviderError('Freesound user OAuth is required for original downloads.', 'AUTH_REQUIRED');
    const url = `${this.apiBase}/sounds/${encodeURIComponent(providerAssetId)}/download/`;
    const response = await this.fetch(url, { headers: { Authorization: `Bearer ${accessToken}` }, redirect: 'follow' });
    if (response.status === 401) throw new ProviderError('Provider authorization expired.', 'AUTH_EXPIRED');
    if (response.status === 429) throw new ProviderError('Provider rate limit reached.', 'RATE_LIMITED');
    if (!response.ok) throw new ProviderError(`Freesound download failed (${response.status}).`, 'PROVIDER_FAILURE');
    return { stream: response.body, contentType: response.headers?.get?.('content-type') || null };
  }

  captureLicense(asset) {
    return {
      licenseId: String(asset.licenseId || '').trim(),
      licenseText: String(asset.licenseText || '').trim() || null,
      attribution: asset.attribution || `${asset.title} by ${asset.artist} — ${asset.sourceUrl}`,
      attributionRequired: true,
    };
  }

  attribution(asset) { return this.captureLicense(asset).attribution; }
  revocation(asset, current) {
    if (current?.deleted) return { revoked: true, reason: 'provider-deleted' };
    if (current?.licenseId && current.licenseId !== asset.licenseId) return { revoked: true, reason: 'license-changed' };
    return { revoked: false };
  }
}

class YouTubeAudioLibraryProvider {
  constructor() {
    this.id = 'youtube-audio-library';
    this.catalogUrl = YOUTUBE_AUDIO_LIBRARY_URL;
  }

  search() {
    return { assisted: true, url: this.catalogUrl, message: 'Search and download in YouTube Studio.' };
  }

  preview() {
    return { assisted: true, url: this.catalogUrl, message: 'Preview using YouTube Audio Library controls.' };
  }

  acquire() {
    throw new ProviderError('Use YouTube Audio Library’s user-initiated download, then import the file.', 'ASSISTED_IMPORT_REQUIRED');
  }

  captureLicense(asset) {
    return {
      licenseId: String(asset.licenseId || '').trim(),
      licenseText: String(asset.licenseText || '').trim() || null,
      attribution: String(asset.attribution || '').trim() || null,
      attributionRequired: Boolean(asset.attributionRequired),
    };
  }

  attribution(asset) { return this.captureLicense(asset).attribution; }
  revocation(asset, current) {
    if (current?.licenseId && current.licenseId !== asset.licenseId) return { revoked: true, reason: 'license-changed' };
    return { revoked: false };
  }
}

class LocalFileProvider {
  constructor() { this.id = 'local-file'; }
  search() { return []; }
  preview(asset) { return { filePath: asset.filePath, cacheable: true }; }
  acquire() { throw new ProviderError('Local files are acquired through an explicit user import.', 'LOCAL_IMPORT_REQUIRED'); }
  captureLicense(asset) {
    return {
      licenseId: String(asset.licenseId || 'user-attestation').trim(),
      licenseText: String(asset.licenseText || '').trim() || null,
      attribution: String(asset.attribution || '').trim() || null,
      attributionRequired: Boolean(asset.attributionRequired),
    };
  }
  attribution(asset) { return this.captureLicense(asset).attribution; }
  revocation() { return { revoked: false }; }
}

module.exports = {
  FREESOUND_API, YOUTUBE_AUDIO_LIBRARY_URL, FreesoundProvider, LocalFileProvider,
  ProviderError, YouTubeAudioLibraryProvider,
};
