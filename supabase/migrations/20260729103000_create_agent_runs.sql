create schema if not exists ctrlb_private;

create table ctrlb_private.agent_runs (
  id uuid primary key,
  owner_id text not null,
  project_id text not null,
  project_revision bigint not null check (project_revision >= 0),
  status text not null check (status in (
    'waiting_for_evidence', 'waiting_for_user', 'proposal_ready',
    'partial', 'failed', 'cancelled'
  )),
  events jsonb not null check (jsonb_typeof(events) = 'array'),
  inventory jsonb not null check (jsonb_typeof(inventory) = 'object'),
  decision jsonb not null check (jsonb_typeof(decision) = 'object'),
  state_version bigint not null default 1 check (state_version > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index agent_runs_owner_updated_idx
  on ctrlb_private.agent_runs (owner_id, updated_at desc);

create index agent_runs_project_idx
  on ctrlb_private.agent_runs (owner_id, project_id, updated_at desc);

alter table ctrlb_private.agent_runs enable row level security;

revoke all on ctrlb_private.agent_runs from public, anon, authenticated;
grant select, insert, update on ctrlb_private.agent_runs to postgres, service_role;

comment on table ctrlb_private.agent_runs is
  'Durable owner-scoped agent state and safe public activity; server access only.';
