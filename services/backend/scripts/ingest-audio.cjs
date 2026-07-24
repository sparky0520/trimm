'use strict';

const path = require('node:path');
const { ingestTrack } = require('../server/audio-ingestion.cjs');

function parse(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith('--')) throw new Error(`Unexpected argument: ${key}`);
    if (['--rights-confirmed', '--attribution-required', '--publish'].includes(key)) values[key.slice(2)] = true;
    else values[key.slice(2)] = argv[++index];
  }
  return values;
}

async function main() {
  const args = parse(process.argv.slice(2));
  const result = await ingestTrack({
    id: args.id, filePath: args.file ? path.resolve(args.file) : '', provider: args.provider || 'local-file',
    providerAssetId: args['provider-asset-id'], sourceUrl: args['source-url'], title: args.title, artist: args.artist,
    licenseId: args.license, licenseText: args['license-text'], rightsConfirmed: args['rights-confirmed'],
    rightsArtifact: args['rights-artifact'] ? { type: 'license-or-receipt', path: path.resolve(args['rights-artifact']) } : null,
    attributionRequired: args['attribution-required'], attribution: args.attribution,
    allowedUse: String(args['allowed-use'] || '').split(',').map((value) => value.trim()).filter(Boolean),
    durationMs: args['duration-ms'], publish: args.publish, outputDir: args.output,
    publicBaseUrl: args['public-base-url'],
  });
  process.stdout.write(`${JSON.stringify(result.item, null, 2)}\n`);
}

main().catch((error) => { process.stderr.write(`Audio import failed: ${error.message}\n`); process.exitCode = 1; });
