'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.join(__dirname, '..', '..', '..');
const backend = fs.readFileSync(path.join(root, 'services', 'backend', 'server', 'mobile-planner.cjs'), 'utf8');
const render = fs.readFileSync(path.join(root, 'render.yaml'), 'utf8');
const vercel = fs.readFileSync(path.join(root, 'vercel.json'), 'utf8');

test('billing routes and database secrets belong to the Render backend only', () => {
  assert.match(backend, /\/v1\/billing\/catalog/);
  assert.match(backend, /\/v1\/billing\/status/);
  assert.match(backend, /\/v1\/billing\/webhook/);
  assert.match(render, /key: SUPABASE_DB_URL/);
  assert.doesNotMatch(vercel, /\/v1\/billing/);
  assert.equal(fs.existsSync(path.join(root, 'api', 'billing.js')), false);
});

test('production backend fails closed without its billing database', () => {
  assert.match(backend, /NODE_ENV === 'production' && !process\.env\.SUPABASE_DB_URL/);
  assert.match(backend, /Production billing database is not configured/);
});
