'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');

test('shared backend is configured as a non-secret package script', () => {
  const packageJson = require('../../../package.json');
  assert.equal(packageJson.scripts['backend:serve'], 'node services/backend/server/server.cjs');
  const source = require('node:fs').readFileSync(require('node:path').join(__dirname, '..', 'server', 'server.cjs'), 'utf8');
  assert.match(source, /\/v1\/transcribe/);
  assert.match(source, /\/v1\/derivatives\/jobs/);
  assert.match(source, /\/v1\/derivatives\/activity/);
  assert.doesNotMatch(source, /sk-[a-zA-Z0-9]{20}/);
});

test('Render backend serves the public privacy policy without authentication', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const serverDirectory = path.join(__dirname, '..', 'server');
  const source = fs.readFileSync(path.join(serverDirectory, 'server.cjs'), 'utf8');
  const policy = fs.readFileSync(path.join(serverDirectory, 'privacy.html'), 'utf8');

  assert.match(source, /requestUrl\.pathname === '\/privacy'/);
  assert.match(source, /return html\(response, 200, privacyPolicy\)/);
  assert.match(policy, /trimm Privacy Policy/);
  assert.match(policy, /darshjain05\.in@gmail\.com/);
});
