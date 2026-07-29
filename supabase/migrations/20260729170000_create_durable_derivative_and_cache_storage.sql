create table ctrlb_private.derivative_owner_state (
  owner_id text primary key,
  payload jsonb not null default '{"jobs":[],"idempotency":[],"results":[],"activity":[],"monthlyUsage":[]}'::jsonb,
  state_version bigint not null default 1,
  updated_at timestamptz not null default now()
);

create table ctrlb_private.derivative_blobs (
  job_id uuid primary key,
  owner_id text not null references ctrlb_private.derivative_owner_state(owner_id) on delete cascade,
  bytes bytea not null,
  updated_at timestamptz not null default now()
);

create index derivative_blobs_owner_idx on ctrlb_private.derivative_blobs(owner_id);

create table ctrlb_private.semantic_cache_entries (
  id uuid primary key default gen_random_uuid(),
  owner_id text not null,
  project_id text not null,
  cache_type text not null check (cache_type in ('provider_context', 'media_index')),
  source_fingerprint text not null,
  range_start_ms bigint not null check (range_start_ms >= 0),
  range_end_ms bigint not null check (range_end_ms > range_start_ms),
  transform jsonb not null,
  model text not null,
  model_version text not null,
  analyzer_version text not null,
  value jsonb not null,
  provenance jsonb not null,
  cost_micros bigint not null default 0 check (cost_micros >= 0),
  hit_count bigint not null default 0 check (hit_count >= 0),
  created_at timestamptz not null default now(),
  last_hit_at timestamptz,
  expires_at timestamptz not null,
  unique (
    owner_id, project_id, cache_type, source_fingerprint,
    range_start_ms, range_end_ms, transform, model, model_version, analyzer_version
  )
);

create index semantic_cache_lookup_idx on ctrlb_private.semantic_cache_entries
  (owner_id, project_id, cache_type, source_fingerprint, expires_at);
create index semantic_cache_range_idx on ctrlb_private.semantic_cache_entries
  (owner_id, project_id, source_fingerprint, range_start_ms, range_end_ms);

alter table ctrlb_private.derivative_owner_state enable row level security;
alter table ctrlb_private.derivative_blobs enable row level security;
alter table ctrlb_private.semantic_cache_entries enable row level security;

revoke all on ctrlb_private.derivative_owner_state from public, anon, authenticated;
revoke all on ctrlb_private.derivative_blobs from public, anon, authenticated;
revoke all on ctrlb_private.semantic_cache_entries from public, anon, authenticated;
grant select, insert, update, delete on ctrlb_private.derivative_owner_state to postgres, service_role;
grant select, insert, update, delete on ctrlb_private.derivative_blobs to postgres, service_role;
grant select, insert, update, delete on ctrlb_private.semantic_cache_entries to postgres, service_role;
