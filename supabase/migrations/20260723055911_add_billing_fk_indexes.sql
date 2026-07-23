create index billing_events_customer_fk_idx
  on ctrlb_private.billing_events (provider, provider_customer_id);

create index billing_accounts_event_fk_idx
  on ctrlb_private.billing_accounts (provider, event_id);
