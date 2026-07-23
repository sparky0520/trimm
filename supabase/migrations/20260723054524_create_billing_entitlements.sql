create schema if not exists ctrlb_private;

revoke all on schema ctrlb_private from public, anon, authenticated;

create table ctrlb_private.billing_customers (
  provider text not null check (provider in ('paddle', 'google_play')),
  provider_customer_id text not null,
  canonical_user_id text not null,
  created_at timestamptz not null default now(),
  primary key (provider, provider_customer_id)
);

create index billing_customers_user_idx
  on ctrlb_private.billing_customers (canonical_user_id);

create table ctrlb_private.billing_events (
  provider text not null check (provider in ('paddle', 'google_play')),
  event_id text not null,
  provider_customer_id text not null,
  canonical_user_id text not null,
  plan_id text not null check (plan_id in ('free', 'basic', 'pro')),
  state text not null check (state in (
    'trialing', 'active', 'grace_period', 'past_due',
    'cancelled', 'expired', 'refunded', 'revoked'
  )),
  occurred_at timestamptz not null,
  period_ends_at timestamptz,
  received_at timestamptz not null default now(),
  primary key (provider, event_id),
  foreign key (provider, provider_customer_id)
    references ctrlb_private.billing_customers (provider, provider_customer_id)
);

create index billing_events_user_time_idx
  on ctrlb_private.billing_events (canonical_user_id, occurred_at desc);

create table ctrlb_private.billing_accounts (
  canonical_user_id text primary key,
  provider text not null,
  event_id text not null,
  provider_customer_id text not null,
  plan_id text not null check (plan_id in ('free', 'basic', 'pro')),
  state text not null check (state in (
    'trialing', 'active', 'grace_period', 'past_due',
    'cancelled', 'expired', 'refunded', 'revoked'
  )),
  occurred_at timestamptz not null,
  period_ends_at timestamptz,
  updated_at timestamptz not null default now(),
  foreign key (provider, event_id)
    references ctrlb_private.billing_events (provider, event_id)
);

alter table ctrlb_private.billing_customers enable row level security;
alter table ctrlb_private.billing_events enable row level security;
alter table ctrlb_private.billing_accounts enable row level security;

revoke all on all tables in schema ctrlb_private from public, anon, authenticated;
grant usage on schema ctrlb_private to postgres, service_role;
grant select, insert, update on all tables in schema ctrlb_private to postgres, service_role;

comment on schema ctrlb_private is
  'Server-owned ctrlB records. Never expose through the Supabase Data API.';
