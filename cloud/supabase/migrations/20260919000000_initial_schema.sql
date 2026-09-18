-- Initial Schema (Squashed)

create schema if not exists trimm_private;

create table trimm_private.agent_runs (
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
  updated_at timestamptz not null default now(),
  attempts jsonb not null default '[]'::jsonb check (jsonb_typeof(attempts) = 'array'),
  recovery jsonb check (recovery is null or jsonb_typeof(recovery) = 'object'),
  usage jsonb not null default '{"calls":[],"inputTokens":0,"outputTokens":0,"thinkingTokens":0,"cachedTokens":0,"totalTokens":0,"estimatedCostMicros":0}'::jsonb check (jsonb_typeof(usage) = 'object')
);

create index agent_runs_owner_updated_idx on trimm_private.agent_runs (owner_id, updated_at desc);
create index agent_runs_project_idx on trimm_private.agent_runs (owner_id, project_id, updated_at desc);

alter table trimm_private.agent_runs enable row level security;
revoke all on trimm_private.agent_runs from public, anon, authenticated;
grant select, insert, update on trimm_private.agent_runs to postgres, service_role;

comment on table trimm_private.agent_runs is 'Durable owner-scoped agent state and safe public activity; server access only.';
comment on column trimm_private.agent_runs.attempts is 'Bounded original and repaired model outputs with safe validation errors; server access only.';
comment on column trimm_private.agent_runs.recovery is 'Safe proposal repair status and retry/clarification availability.';
comment on column trimm_private.agent_runs.usage is 'Deduplicated provider token usage and estimated USD micros for reader-visible per-run cost reporting.';

create table trimm_private.semantic_cache_entries (
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

create index semantic_cache_lookup_idx on trimm_private.semantic_cache_entries (owner_id, project_id, cache_type, source_fingerprint, expires_at);
create index semantic_cache_range_idx on trimm_private.semantic_cache_entries (owner_id, project_id, source_fingerprint, range_start_ms, range_end_ms);

alter table trimm_private.semantic_cache_entries enable row level security;
revoke all on trimm_private.semantic_cache_entries from public, anon, authenticated;
grant select, insert, update, delete on trimm_private.semantic_cache_entries to postgres, service_role;

CREATE TABLE public.chats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT 'New Chat',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    project_data JSONB
);

CREATE TYPE checkpoint_status AS ENUM ('pending', 'processing', 'completed', 'failed');

CREATE TABLE public.checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_id UUID NOT NULL REFERENCES public.chats(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES public.checkpoints(id) ON DELETE SET NULL,
    status checkpoint_status NOT NULL DEFAULT 'pending',
    prompt TEXT,
    edit_instructions JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.chats ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.checkpoints ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can delete their own chats" ON public.chats FOR DELETE TO authenticated USING (auth.uid()::text = user_id);
CREATE POLICY "Users can insert their own chats" ON public.chats FOR INSERT TO authenticated WITH CHECK (auth.uid()::text = user_id);
CREATE POLICY "Users can update their own chats" ON public.chats FOR UPDATE TO authenticated USING (auth.uid()::text = user_id);
CREATE POLICY "Users can view their own chats" ON public.chats FOR SELECT TO authenticated USING (auth.uid()::text = user_id);

CREATE POLICY "Users can delete checkpoints of their chats" ON public.checkpoints FOR DELETE TO authenticated USING (EXISTS (SELECT 1 FROM chats WHERE chats.id = checkpoints.chat_id AND chats.user_id = auth.uid()::text));
CREATE POLICY "Users can insert checkpoints to their chats" ON public.checkpoints FOR INSERT TO authenticated WITH CHECK (EXISTS (SELECT 1 FROM chats WHERE chats.id = checkpoints.chat_id AND chats.user_id = auth.uid()::text));
CREATE POLICY "Users can update checkpoints of their chats" ON public.checkpoints FOR UPDATE TO authenticated USING (EXISTS (SELECT 1 FROM chats WHERE chats.id = checkpoints.chat_id AND chats.user_id = auth.uid()::text));
CREATE POLICY "Users can view checkpoints of their chats" ON public.checkpoints FOR SELECT TO authenticated USING (EXISTS (SELECT 1 FROM chats WHERE chats.id = checkpoints.chat_id AND chats.user_id = auth.uid()::text));
