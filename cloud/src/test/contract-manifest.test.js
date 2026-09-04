import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

test('cloud publishes one versioned public contract manifest', () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'contracts', 'contract-manifest.json'), 'utf8'));
  assert.equal(manifest.manifestVersion, 1);
  assert.equal(manifest.owner, 'sparky0520/trimm-cloud');
  for (const name of ['planner', 'evidencePacket', 'editOperations', 'derivativeJobs', 'mediaIndex', 'accountLifecycle']) {
    assert.equal(manifest.contracts[name], 1);
  }
  for (const name of ['agentRuns', 'activityEvents', 'videoToolRequests', 'editPrograms']) assert.equal(manifest.contracts[name], 2);
  for (const name of ['conversations', 'conversationMessages']) assert.equal(manifest.contracts[name], 3);
});
