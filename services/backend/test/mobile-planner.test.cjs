'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const plannerCore = require('../server/planner-core.cjs');

test('mobile planner is configured as a non-secret package script', () => {
  const packageJson = require('../../../package.json');
  assert.equal(packageJson.scripts['planner:serve'], 'node services/backend/server/mobile-planner.cjs');
  assert.equal(packageJson.scripts['backend:serve'], 'node services/backend/server/mobile-planner.cjs');
  const source = require('node:fs').readFileSync(require('node:path').join(__dirname, '..', 'server', 'mobile-planner.cjs'), 'utf8');
  assert.match(source, /process\.env\.MOONSHOT_API_KEY/);
  assert.match(source, /\/v1\/desktop\/plan/);
  assert.match(source, /\/v1\/mobile\/plan/);
  assert.match(source, /\/v1\/mobile\/orchestrate/);
  assert.match(source, /\/v1\/transcribe/);
  assert.match(source, /\/v1\/derivatives\/jobs/);
  assert.match(source, /\/v1\/derivatives\/activity/);
  assert.doesNotMatch(source, /sk-[a-zA-Z0-9]{20}/);
});

test('Render backend serves the public privacy policy without authentication', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const serverDirectory = path.join(__dirname, '..', 'server');
  const source = fs.readFileSync(path.join(serverDirectory, 'mobile-planner.cjs'), 'utf8');
  const policy = fs.readFileSync(path.join(serverDirectory, 'privacy.html'), 'utf8');

  assert.match(source, /requestUrl\.pathname === '\/privacy'/);
  assert.match(source, /return html\(response, 200, privacyPolicy\)/);
  assert.match(policy, /ctrlB Privacy Policy/);
  assert.match(policy, /darshjain05\.in@gmail\.com/);
});

test('mobile planning excludes operations without render support or frame evidence', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  for (const name of ['planner-core.cjs', 'mobile-planner.cjs']) {
    const source = fs.readFileSync(path.join(__dirname, '..', 'server', name), 'utf8');
    assert.doesNotMatch(source.match(/const allowed = new Set\(\[[^\n]+/)[0], /set_color_grade|add_transition|add_motion_preset/);
    assert.match(source, /Color requires frame evidence/);
    assert.match(source, /Transitions and motion presets are unavailable/);
  }
});

test('mobile planner accepts only bounded evidence packets and grounded output references', () => {
  const request = {
    prompt: 'make a first cut',
    media: { durationMs: 1000, width: 1080, height: 1920, rotation: 0, hasAudio: true },
    preferences: {},
    operations: [],
    projectRevision: 4,
    evidence: {
      schemaVersion: 1,
      evidenceFingerprint: 'graph-version',
      items: [{
        id: 'sentence-1', type: 'TRANSCRIPT', sourceId: 'source-1',
        range: { startMs: 0, endMs: 500 }, confidence: 0.9, summary: 'Hello',
        derivedFromEvidenceIds: [],
      }],
      exhaustedTypes: [],
      omittedCounts: {},
    },
  };
  assert.equal(plannerCore.validateRequest(request), true);
  assert.equal(plannerCore.validateRequest({ ...request, evidence: { ...request.evidence, items: [
    { ...request.evidence.items[0], artifactPath: '/private/frame.jpg' },
  ] } }), false);
  assert.throws(() => plannerCore.validateProposal({
    type: 'proposal', expectedRevision: 4, summary: 'cut', operations: [],
    evidenceIds: ['invented'],
  }, 4, new Set(['sentence-1'])), /invented evidence/);
  assert.equal(plannerCore.validateProposal({
    type: 'proposal', expectedRevision: 4, summary: 'cut', operations: [],
    evidenceIds: ['sentence-1'],
  }, 4, new Set(['sentence-1'])).summary, 'cut');
});
