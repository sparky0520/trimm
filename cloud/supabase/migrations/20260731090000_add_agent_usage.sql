alter table trimm_private.agent_runs
  add column if not exists usage jsonb not null default
    '{"calls":[],"inputTokens":0,"outputTokens":0,"thinkingTokens":0,"cachedTokens":0,"totalTokens":0,"estimatedCostMicros":0}'::jsonb
  check (jsonb_typeof(usage) = 'object');

comment on column trimm_private.agent_runs.usage is
  'Deduplicated provider token usage and estimated USD micros for reader-visible per-run cost reporting.';
