alter table trimm_private.agent_runs
  add column attempts jsonb not null default '[]'::jsonb
    check (jsonb_typeof(attempts) = 'array'),
  add column recovery jsonb
    check (recovery is null or jsonb_typeof(recovery) = 'object');

comment on column trimm_private.agent_runs.attempts is
  'Bounded original and repaired model outputs with safe validation errors; server access only.';
comment on column trimm_private.agent_runs.recovery is
  'Safe proposal repair status and retry/clarification availability.';
