'use strict';

const postgres = require('postgres');

function storeError(message, status = 503, code = 'agent_run_store_failed') {
  return Object.assign(new Error(message), { status, code });
}

function clone(value) {
  return structuredClone(value);
}

class VolatileAgentRunStore {
  constructor({ records } = {}) {
    this.records = records || new Map();
  }

  async create(run) {
    if (this.records.has(run.id)) throw storeError('Agent run already exists.', 409, 'agent_run_conflict');
    const stored = { ...clone(run), stateVersion: 1 };
    this.records.set(run.id, stored);
    return clone(stored);
  }

  async read(ownerId, runId) {
    const run = this.records.get(runId);
    return run?.ownerId === ownerId ? clone(run) : null;
  }

  async save(run, expectedVersion) {
    const current = this.records.get(run.id);
    if (!current || current.ownerId !== run.ownerId || current.stateVersion !== expectedVersion) {
      throw storeError('Agent run changed while it was being updated.', 409, 'agent_run_conflict');
    }
    const stored = { ...clone(run), stateVersion: expectedVersion + 1 };
    this.records.set(run.id, stored);
    return clone(stored);
  }
}

function record(row) {
  if (!row) return null;
  return {
    id: row.id,
    ownerId: row.owner_id,
    projectId: row.project_id,
    projectRevision: Number(row.project_revision),
    status: row.status,
    events: row.events,
    inventory: row.inventory,
    decision: row.decision,
    attempts: row.attempts || [],
    recovery: row.recovery,
    usage: row.usage || { calls: [], inputTokens: 0, outputTokens: 0, thinkingTokens: 0, cachedTokens: 0, totalTokens: 0, estimatedCostMicros: 0 },
    stateVersion: Number(row.state_version),
  };
}

class PostgresAgentRunStore {
  constructor({ databaseUrl, sql } = {}) {
    if (!sql && !databaseUrl) throw storeError('Agent run database is not configured.', 503, 'agent_run_database_unconfigured');
    this.sql = sql || postgres(databaseUrl, {
      max: 4,
      idle_timeout: 20,
      connect_timeout: 10,
      prepare: false,
      ssl: 'require',
    });
  }

  async create(run) {
    const [row] = await this.sql`
      insert into trimm_private.agent_runs
        (id, owner_id, project_id, project_revision, status, events, inventory, decision, attempts, recovery, usage)
      values (
        ${run.id}, ${run.ownerId}, ${run.projectId}, ${run.projectRevision},
        ${run.status}, ${this.sql.json(run.events)}, ${this.sql.json(run.inventory)},
        ${this.sql.json(run.decision)}, ${this.sql.json(run.attempts || [])}, ${this.sql.json(run.recovery)},
        ${this.sql.json(run.usage || { calls: [] })}
      )
      returning id, owner_id, project_id, project_revision, status,
                events, inventory, decision, attempts, recovery, usage, state_version
    `;
    return record(row);
  }

  async read(ownerId, runId) {
    const [row] = await this.sql`
      select id, owner_id, project_id, project_revision, status,
             events, inventory, decision, attempts, recovery, usage, state_version
      from trimm_private.agent_runs
      where id = ${runId} and owner_id = ${ownerId}
    `;
    return record(row);
  }

  async save(run, expectedVersion) {
    const [row] = await this.sql`
      update trimm_private.agent_runs set
        project_revision = ${run.projectRevision},
        status = ${run.status},
        events = ${this.sql.json(run.events)},
        inventory = ${this.sql.json(run.inventory)},
        decision = ${this.sql.json(run.decision)},
        attempts = ${this.sql.json(run.attempts || [])},
        recovery = ${this.sql.json(run.recovery)},
        usage = ${this.sql.json(run.usage || { calls: [] })},
        state_version = state_version + 1,
        updated_at = now()
      where id = ${run.id}
        and owner_id = ${run.ownerId}
        and state_version = ${expectedVersion}
      returning id, owner_id, project_id, project_revision, status,
                events, inventory, decision, attempts, recovery, usage, state_version
    `;
    if (!row) throw storeError('Agent run changed while it was being updated.', 409, 'agent_run_conflict');
    return record(row);
  }
}

module.exports = {
  PostgresAgentRunStore,
  VolatileAgentRunStore,
  record,
  storeError,
};
