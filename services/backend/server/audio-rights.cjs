'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const ALLOWED_SCOPES = new Set(['personal', 'commercial', 'rendered-video', 'redistribution']);
const ID_PATTERN = /^[a-z0-9][a-z0-9._-]{1,79}$/;

async function sha256(filePath) {
  const digest = crypto.createHash('sha256');
  await new Promise((resolve, reject) => fs.createReadStream(filePath)
    .on('data', (chunk) => digest.update(chunk)).on('end', resolve).on('error', reject));
  return digest.digest('hex');
}

function quarantineReasons(asset) {
  const reasons = [];
  if (!asset.provider || !asset.providerAssetId || !asset.sourceUrl) reasons.push('missing-provenance');
  if (!asset.licenseId && !asset.licenseText) reasons.push('unclear-license');
  if (!asset.rightsAttested) reasons.push('rights-not-attested');
  if (!asset.acquiredAt || Number.isNaN(Date.parse(asset.acquiredAt))) reasons.push('invalid-acquisition-time');
  if (!/^[a-f0-9]{64}$/i.test(asset.checksum || '')) reasons.push('invalid-checksum');
  if (!Array.isArray(asset.allowedUse) || !asset.allowedUse.includes('rendered-video') ||
      asset.allowedUse.some((scope) => !ALLOWED_SCOPES.has(scope))) reasons.push('invalid-allowed-use');
  if (asset.attributionRequired && !asset.attribution) reasons.push('missing-attribution');
  if (asset.revokedAt) reasons.push(asset.revocationReason || 'revoked');
  return [...new Set(reasons)];
}

function normalizeAsset(input) {
  if (!ID_PATTERN.test(input.id || '')) throw new Error('Invalid audio asset id.');
  let sourceUrl;
  try { sourceUrl = new URL(input.sourceUrl).toString(); } catch { throw new Error('A valid source URL is required.'); }
  if (!['https:', 'file:'].includes(new URL(sourceUrl).protocol)) throw new Error('Source URL must use HTTPS or file:.');
  const asset = {
    id: input.id, kind: input.kind || 'MUSIC', title: String(input.title || input.id),
    artist: String(input.artist || ''), provider: String(input.provider || ''),
    providerAssetId: String(input.providerAssetId || ''), sourceUrl,
    acquiredAt: String(input.acquiredAt || new Date().toISOString()), checksum: String(input.checksum || '').toLowerCase(),
    licenseId: String(input.licenseId || ''), licenseText: input.licenseText ? String(input.licenseText) : null,
    attribution: input.attribution ? String(input.attribution) : null,
    attributionRequired: Boolean(input.attributionRequired), allowedUse: [...new Set(input.allowedUse || [])],
    rightsAttested: input.rightsAttested === true, rightsArtifact: input.rightsArtifact || null,
    userScoped: input.userScoped !== false, redistributionAllowed: (input.allowedUse || []).includes('redistribution'),
    cachedPath: input.cachedPath ? path.resolve(input.cachedPath) : null,
    durationMs: Math.max(0, Math.round(Number(input.durationMs || 0))),
    revokedAt: input.revokedAt || null, revocationReason: input.revocationReason || null,
  };
  const reasons = quarantineReasons(asset);
  return { ...asset, status: reasons.length ? 'quarantined' : 'ready', quarantineReasons: reasons };
}

async function verifyAsset(asset) {
  let updated = normalizeAsset(asset);
  if (updated.cachedPath) {
    try {
      if (await sha256(updated.cachedPath) !== updated.checksum) {
        updated = { ...updated, status: 'quarantined', quarantineReasons: [...new Set([...updated.quarantineReasons, 'checksum-mismatch'])] };
      }
    } catch {
      updated = { ...updated, status: 'quarantined', quarantineReasons: [...new Set([...updated.quarantineReasons, 'cached-file-missing'])] };
    }
  }
  return updated;
}

function assertRenderable(asset) {
  const checked = normalizeAsset(asset);
  if (checked.status !== 'ready') throw new Error(`Audio asset '${asset.id}' is quarantined: ${checked.quarantineReasons.join(', ')}.`);
  return checked;
}

async function importUserFile(filePath, metadata, dependencies = {}) {
  if (metadata?.rightsAttested !== true) throw new Error('Explicit rights attestation is required.');
  const absolute = path.resolve(filePath);
  const stat = await fs.promises.stat(absolute);
  if (!stat.isFile()) throw new Error('Audio import must be a file.');
  const checksum = await (dependencies.sha256 || sha256)(absolute);
  return verifyAsset({
    ...metadata, provider: metadata.provider || 'local-file',
    providerAssetId: metadata.providerAssetId || checksum,
    sourceUrl: metadata.sourceUrl || new URL(`file:///${absolute.replace(/\\/g, '/')}`).toString(),
    checksum, cachedPath: absolute, acquiredAt: metadata.acquiredAt || new Date().toISOString(),
    userScoped: true,
  });
}

async function auditAsset(asset, provider, currentMetadata) {
  let updated = await verifyAsset(asset);
  const decision = provider.revocation(updated, currentMetadata);
  if (decision.revoked) updated = normalizeAsset({
    ...updated, revokedAt: new Date().toISOString(), revocationReason: decision.reason,
  });
  return updated;
}

function publicCatalogItem(asset) {
  const ready = assertRenderable(asset);
  if (ready.userScoped && !ready.redistributionAllowed) {
    throw new Error('Provider media cannot be published without explicit redistribution rights.');
  }
  return ready;
}

module.exports = {
  ALLOWED_SCOPES, assertRenderable, auditAsset, importUserFile, normalizeAsset,
  publicCatalogItem, quarantineReasons, sha256, verifyAsset,
};
