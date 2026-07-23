'use strict';

const postgres = require('postgres');
const { billingError } = require('./billing-core.cjs');

function record(row) {
  if (!row) return null;
  return Object.freeze({
    eventId: row.event_id,
    provider: row.provider,
    providerCustomerId: row.provider_customer_id,
    canonicalUserId: row.canonical_user_id,
    planId: row.plan_id,
    state: row.state,
    occurredAt: new Date(row.occurred_at).toISOString(),
    periodEndsAt: row.period_ends_at ? new Date(row.period_ends_at).toISOString() : null,
  });
}

class PostgresBillingStore {
  constructor({ databaseUrl, sql } = {}) {
    if (!sql && !databaseUrl) throw billingError('Billing database is not configured.', 503, 'billing_database_unconfigured');
    this.sql = sql || postgres(databaseUrl, {
      max: 2,
      idle_timeout: 20,
      connect_timeout: 10,
      prepare: false,
      ssl: 'require',
    });
  }

  async account(userId, sql = this.sql) {
    const [row] = await sql`
      select event_id, provider, provider_customer_id, canonical_user_id,
             plan_id, state, occurred_at, period_ends_at
      from ctrlb_private.billing_accounts
      where canonical_user_id = ${userId}
    `;
    return record(row);
  }

  async apply(event) {
    return this.sql.begin(async (tx) => {
      const customer = await tx`
        insert into ctrlb_private.billing_customers
          (provider, provider_customer_id, canonical_user_id)
        values (${event.provider}, ${event.providerCustomerId}, ${event.canonicalUserId})
        on conflict (provider, provider_customer_id) do update
          set provider_customer_id = excluded.provider_customer_id
        returning canonical_user_id
      `;
      if (customer[0]?.canonical_user_id !== event.canonicalUserId) {
        throw billingError('Billing account ownership conflict.', 409, 'billing_owner_conflict');
      }

      const inserted = await tx`
        insert into ctrlb_private.billing_events
          (provider, event_id, provider_customer_id, canonical_user_id,
           plan_id, state, occurred_at, period_ends_at)
        values (
          ${event.provider}, ${event.eventId}, ${event.providerCustomerId},
          ${event.canonicalUserId}, ${event.planId}, ${event.state},
          ${event.occurredAt}, ${event.periodEndsAt}
        )
        on conflict (provider, event_id) do nothing
        returning event_id
      `;
      if (!inserted.length) return { duplicate: true, account: await this.account(event.canonicalUserId, tx) };

      await tx`
        insert into ctrlb_private.billing_accounts
          (canonical_user_id, provider, event_id, provider_customer_id,
           plan_id, state, occurred_at, period_ends_at)
        values (
          ${event.canonicalUserId}, ${event.provider}, ${event.eventId},
          ${event.providerCustomerId}, ${event.planId}, ${event.state},
          ${event.occurredAt}, ${event.periodEndsAt}
        )
        on conflict (canonical_user_id) do update set
          provider = excluded.provider,
          event_id = excluded.event_id,
          provider_customer_id = excluded.provider_customer_id,
          plan_id = excluded.plan_id,
          state = excluded.state,
          occurred_at = excluded.occurred_at,
          period_ends_at = excluded.period_ends_at,
          updated_at = now()
        where excluded.occurred_at >= ctrlb_private.billing_accounts.occurred_at
      `;
      return { duplicate: false, account: await this.account(event.canonicalUserId, tx) };
    });
  }
}

module.exports = { PostgresBillingStore, record };
