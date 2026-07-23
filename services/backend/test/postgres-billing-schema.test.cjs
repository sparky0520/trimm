'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const migrationDirectory = path.join(__dirname, '..', '..', '..', 'supabase', 'migrations');
const migration = fs.readFileSync(path.join(migrationDirectory,
  fs.readdirSync(migrationDirectory).find((name) => name.endsWith('_create_billing_entitlements.sql'))), 'utf8');

test('billing tables stay private and reject public Data API roles', () => {
  assert.match(migration, /create schema if not exists ctrlb_private/i);
  assert.match(migration, /revoke all on schema ctrlb_private from public, anon, authenticated/i);
  assert.match(migration, /enable row level security/g);
  assert.doesNotMatch(migration, /create policy/i);
});

test('billing ledger enforces provider replay and ownership keys', () => {
  assert.match(migration, /primary key \(provider, event_id\)/i);
  assert.match(migration, /primary key \(provider, provider_customer_id\)/i);
  assert.match(migration, /canonical_user_id text primary key/i);
});
