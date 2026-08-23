'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('cloud publishes one versioned public contract manifest', () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', '..', 'contracts', 'contract-manifest.json'), 'utf8'));
  assert.equal(manifest.manifestVersion, 1);
  assert.equal(manifest.owner, 'sparky0520/ctrlb-cloud');
  for (const name of ['planner', 'evidencePacket', 'editOperations', 'derivativeJobs', 'mediaIndex', 'accountLifecycle']) {
    assert.equal(manifest.contracts[name], 1);
  }
  for (const name of ['agentRuns', 'activityEvents', 'videoToolRequests', 'editPrograms']) assert.equal(manifest.contracts[name], 2);
  for (const name of ['conversations', 'conversationMessages']) assert.equal(manifest.contracts[name], 3);
});
