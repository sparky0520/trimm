'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const migration = fs.readFileSync(
  path.join(__dirname, '..', '..', '..', 'supabase', 'migrations', '20260729103000_create_agent_runs.sql'),
  'utf8',
);
const store = fs.readFileSync(path.join(__dirname, '..', 'server', 'agent-run-store.cjs'), 'utf8');

test('agent runs are durable, private, owner scoped, and versioned', () => {
  assert.match(migration, /create table ctrlb_private\.agent_runs/i);
  assert.match(migration, /owner_id text not null/i);
  assert.match(migration, /state_version bigint not null default 1/i);
  assert.match(migration, /enable row level security/i);
  assert.match(migration, /revoke all on ctrlb_private\.agent_runs from public, anon, authenticated/i);
  assert.doesNotMatch(migration, /create policy/i);
});

test('Postgres run updates use ownership and optimistic concurrency', () => {
  assert.match(store, /where id = \$\{run\.id\}[\s\S]*owner_id = \$\{run\.ownerId\}[\s\S]*state_version = \$\{expectedVersion\}/i);
  assert.match(store, /state_version = state_version \+ 1/i);
});
