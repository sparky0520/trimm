'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');

const execFileAsync = promisify(execFile);
const ID_PATTERN = /^[a-z0-9][a-z0-9._-]{1,79}$/;

function assertHttpsUrl(value, label) {
  const parsed = new URL(value);
  if (parsed.protocol !== 'https:') throw new Error(`${label} must use HTTPS.`);
  return parsed.toString();
}

function validateOptions(options) {
  if (!ID_PATTERN.test(options.id || '')) throw new Error('id must be 2-80 lowercase letters, numbers, dots, dashes, or underscores.');
  if (!options.rightsConfirmed) throw new Error('Refusing ingestion without --rights-confirmed. Verify the asset license first.');
  if (!String(options.licenseId || '').trim()) throw new Error('licenseId is required.');
  if (options.attributionRequired && !String(options.attribution || '').trim()) throw new Error('Attribution text is required for this license.');
  assertHttpsUrl(options.sourceUrl, 'Source URL');
  assertHttpsUrl(options.publicBaseUrl, 'Public base URL');
}

async function sha256(filePath) {
  const digest = crypto.createHash('sha256');
  await new Promise((resolve, reject) => fs.createReadStream(filePath).on('data', (chunk) => digest.update(chunk)).on('end', resolve).on('error', reject));
  return digest.digest('hex');
}

async function ingestTrack(options, dependencies = {}) {
  validateOptions(options);
  const run = dependencies.execFile || execFileAsync;
  const outputDir = path.resolve(options.outputDir);
  const mediaDir = path.join(outputDir, 'media');
  await fs.promises.mkdir(mediaDir, { recursive: true });

  const metadataResult = await run(options.ytDlpPath || 'yt-dlp', ['--no-playlist', '--dump-single-json', '--skip-download', options.sourceUrl], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
  const upstream = JSON.parse(String(metadataResult.stdout));
  const canonicalSource = assertHttpsUrl(upstream.webpage_url || options.sourceUrl, 'Canonical source URL');
  const host = new URL(canonicalSource).hostname.toLowerCase();
  if (host !== 'youtube.com' && !host.endsWith('.youtube.com') && host !== 'youtu.be') throw new Error('Only YouTube sources are accepted by this ingestion command.');

  const temporaryTemplate = path.join(mediaDir, `${options.id}.partial.%(ext)s`);
  const result = await run(options.ytDlpPath || 'yt-dlp', [
    '--no-playlist', '--extract-audio', '--audio-format', 'mp3', '--audio-quality', '0',
    '--output', temporaryTemplate, '--print', 'after_move:filepath', options.sourceUrl,
  ], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
  const downloaded = String(result.stdout).trim().split(/\r?\n/).filter(Boolean).at(-1);
  if (!downloaded || !path.resolve(downloaded).startsWith(`${mediaDir}${path.sep}`)) throw new Error('yt-dlp did not report a safe output path.');
  const finalPath = path.join(mediaDir, `${options.id}.mp3`);
  await fs.promises.rm(finalPath, { force: true });
  await fs.promises.rename(downloaded, finalPath);

  try {
    const probe = await run(options.ffprobePath || 'ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', finalPath], { encoding: 'utf8' });
    const durationMs = Math.round(Number(String(probe.stdout).trim()) * 1000);
    if (!(durationMs > 0)) throw new Error('ffprobe returned an invalid duration.');
    const base = options.publicBaseUrl.replace(/\/$/, '');
    const mediaUrl = `${base}/media/${encodeURIComponent(options.id)}.mp3`;
    const item = {
      id: options.id, kind: options.kind || 'MUSIC', title: options.title || upstream.title || options.id,
      artist: options.artist || upstream.uploader || upstream.channel || '', assetPath: `media/${options.id}.mp3`,
      previewUrl: mediaUrl, downloadUrl: mediaUrl, sourceUrl: canonicalSource, durationMs,
      bpm: options.bpm == null ? null : Number(options.bpm), energy: Number(options.energy ?? 0.5),
      moods: options.moods || [], licenseId: options.licenseId, checksum: await sha256(finalPath),
      attributionRequired: Boolean(options.attributionRequired), attribution: options.attribution || null,
    };
    const catalogPath = path.join(outputDir, 'catalog.v1.json');
    let document = { version: 1, items: [] };
    try { document = JSON.parse(await fs.promises.readFile(catalogPath, 'utf8')); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    document.version = 1;
    document.items = [...(document.items || []).filter((entry) => entry.id !== item.id), item].sort((a, b) => a.id.localeCompare(b.id));
    await fs.promises.writeFile(`${catalogPath}.tmp`, `${JSON.stringify(document, null, 2)}\n`, 'utf8');
    await fs.promises.rename(`${catalogPath}.tmp`, catalogPath);
    return { item, catalogPath, mediaPath: finalPath };
  } catch (error) {
    await fs.promises.rm(finalPath, { force: true });
    throw error;
  }
}

module.exports = { ingestTrack, validateOptions };
