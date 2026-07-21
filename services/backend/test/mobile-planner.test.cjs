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
  assert.match(source, /\/v1\/transcribe/);
  assert.doesNotMatch(source, /sk-[a-zA-Z0-9]{20}/);
});
