import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

test('shared backend is configured as a non-secret package script', () => {
  const packageJson = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'package.json'), 'utf8'));
  assert.equal(packageJson.scripts['backend:serve'], 'node src/server.js');
  const source = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
  assert.match(source, /process\.env\.MOONSHOT_API_KEY/);
  assert.match(source, /\/v1\/transcribe/);
  assert.match(source, /\/v1\/derivatives\/jobs/);
  assert.match(source, /\/v1\/derivatives\/activity/);
  assert.doesNotMatch(source, /sk-[a-zA-Z0-9]{20}/);
});

test('Render backend serves the public privacy policy without authentication', () => {
  const serverDirectory = path.join(__dirname, '..');
  const source = fs.readFileSync(path.join(serverDirectory, 'server.js'), 'utf8');
  const policy = fs.readFileSync(path.join(serverDirectory, 'privacy.html'), 'utf8');

  assert.match(source, /requestUrl\.pathname === '\/privacy'/);
  assert.match(source, /return html\(response, 200, privacyPolicy\)/);
  assert.match(policy, /trimm Privacy Policy/);
  assert.match(policy, /darshjain05\.in@gmail\.com/);
});
