'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { importUserFile, publicCatalogItem } = require('./audio-rights.cjs');
const { LocalFileProvider, YouTubeAudioLibraryProvider } = require('./audio-providers.cjs');

const PROVIDERS = {
  'local-file': new LocalFileProvider(),
  'youtube-audio-library': new YouTubeAudioLibraryProvider(),
};

function validateOptions(options) {
  if (!options.filePath) throw new Error('filePath is required; download provider media using its documented user control first.');
  if (!options.rightsConfirmed) throw new Error('Refusing import without --rights-confirmed.');
  if (!String(options.providerAssetId || '').trim()) throw new Error('providerAssetId is required.');
  if (!String(options.licenseId || options.licenseText || '').trim()) throw new Error('licenseId or licenseText is required.');
  if (options.attributionRequired && !String(options.attribution || '').trim()) throw new Error('Attribution text is required for this license.');
  if (!Array.isArray(options.allowedUse) || !options.allowedUse.includes('rendered-video')) {
    throw new Error('allowedUse must explicitly include rendered-video.');
  }
  if (!PROVIDERS[options.provider || 'local-file']) throw new Error('Unsupported assisted-import provider.');
  if (options.publish && !options.allowedUse.includes('redistribution')) {
    throw new Error('Publishing provider media requires explicit redistribution rights.');
  }
}

async function probeDuration(filePath, dependencies = {}, ffprobePath = 'ffprobe') {
  const run = dependencies.execFile;
  if (!run) return Math.max(0, Number(dependencies.durationMs || 0));
  const result = await run(ffprobePath, [
    '-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', filePath,
  ], { encoding: 'utf8' });
  return Math.round(Number(String(result.stdout).trim()) * 1000);
}

async function ingestTrack(options, dependencies = {}) {
  validateOptions(options);
  const provider = PROVIDERS[options.provider || 'local-file'];
  const license = provider.captureLicense(options);
  const durationMs = options.durationMs == null ? await probeDuration(options.filePath, dependencies, options.ffprobePath) : Number(options.durationMs);
  const item = await importUserFile(options.filePath, {
    id: options.id, kind: options.kind, title: options.title, artist: options.artist,
    provider: provider.id, providerAssetId: options.providerAssetId, sourceUrl: options.sourceUrl,
    ...license, allowedUse: options.allowedUse, rightsAttested: options.rightsConfirmed,
    rightsArtifact: options.rightsArtifact || null, durationMs,
  }, dependencies);

  if (!options.outputDir) return { item, mediaPath: path.resolve(options.filePath), catalogPath: null };
  if (!options.publish) throw new Error('User-scoped imports must remain in the user cache; omit outputDir or grant redistribution rights with --publish.');
  const publishable = publicCatalogItem(item);
  const outputDir = path.resolve(options.outputDir);
  const mediaDir = path.join(outputDir, 'media');
  await fs.promises.mkdir(mediaDir, { recursive: true });
  const extension = path.extname(options.filePath).toLowerCase() || '.media';
  const finalPath = path.join(mediaDir, `${item.id}${extension}`);
  await fs.promises.copyFile(options.filePath, finalPath);
  const base = String(options.publicBaseUrl || '').replace(/\/$/, '');
  const catalogItem = {
    ...publishable, cachedPath: undefined, assetPath: `media/${item.id}${extension}`,
    previewUrl: `${base}/media/${encodeURIComponent(item.id)}${extension}`,
    downloadUrl: `${base}/media/${encodeURIComponent(item.id)}${extension}`,
  };
  const catalogPath = path.join(outputDir, 'catalog.v1.json');
  let document = { version: 1, items: [] };
  try { document = JSON.parse(await fs.promises.readFile(catalogPath, 'utf8')); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  document.items = [...document.items.filter((entry) => entry.id !== item.id), catalogItem].sort((a, b) => a.id.localeCompare(b.id));
  await fs.promises.writeFile(`${catalogPath}.tmp`, `${JSON.stringify(document, null, 2)}\n`);
  await fs.promises.rename(`${catalogPath}.tmp`, catalogPath);
  return { item: catalogItem, mediaPath: finalPath, catalogPath };
}

module.exports = { ingestTrack, validateOptions };
