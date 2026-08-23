'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..', '..');
const migration = fs.readFileSync(path.join(
  root, 'supabase', 'migrations', '20260729170000_create_durable_derivative_and_cache_storage.sql',
), 'utf8');
const server = fs.readFileSync(path.join(__dirname, '..', 'server', 'server.cjs'), 'utf8');
const store = fs.readFileSync(path.join(__dirname, '..', 'server', 'postgres-derivative-jobs.cjs'), 'utf8');

test('production derivative state, bytes and semantic caches are private durable tables', () => {
  for (const table of ['derivative_owner_state', 'derivative_blobs', 'semantic_cache_entries']) {
    assert.match(migration, new RegExp(`create table trimm_private\\.${table}`, 'i'));
    assert.match(migration, new RegExp(`alter table trimm_private\\.${table} enable row level security`, 'i'));
    assert.match(migration, new RegExp(`revoke all on trimm_private\\.${table} from public, anon, authenticated`, 'i'));
  }
  assert.match(migration, /cache_type in \('provider_context', 'media_index'\)/i);
  assert.match(migration, /hit_count bigint not null default 0/i);
});

test('production selects transactional Postgres derivative storage', () => {
  assert.match(server, /process\.env\.SUPABASE_DB_URL[\s\S]*new PostgresDerivativeJobs/i);
  assert.match(store, /for update/i);
  assert.match(store, /insert into trimm_private\.derivative_blobs/i);
  assert.match(store, /state_version = state_version \+ 1/i);
});
