'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');

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
