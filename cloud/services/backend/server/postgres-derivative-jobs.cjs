'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const postgres = require('postgres');
const { DerivativeJobs } = require('./derivative-jobs.cjs');

function cloneMap(map) {
  return [...map.entries()];
}

function payloadFor(engine) {
  return {
    jobs: cloneMap(engine.jobs),
    idempotency: cloneMap(engine.idempotency),
    activity: cloneMap(engine.activity),
  };
}

function hydrate(engine, payload = {}) {
  for (const name of ['jobs', 'idempotency', 'activity']) {
    engine[name] = new Map(Array.isArray(payload[name]) ? payload[name] : []);
  }
}

class PostgresDerivativeJobs {
  constructor(options = {}) {
    if (!options.sql && !options.databaseUrl) throw new TypeError('Derivative job database is not configured.');
    this.sql = options.sql || postgres(options.databaseUrl, {
      max: 6, idle_timeout: 20, connect_timeout: 10, prepare: false, ssl: 'require',
    });
    this.options = {
      now: options.now,
      ttlMs: options.ttlMs,
      retentionMs: options.retentionMs,
    };
  }

  async withOwner(ownerId, operation) {
    return this.sql.begin(async (sql) => {
      await sql`
        insert into ctrlb_private.derivative_owner_state (owner_id)
        values (${ownerId}) on conflict (owner_id) do nothing
      `;
      const [state] = await sql`
        select payload, state_version from ctrlb_private.derivative_owner_state
        where owner_id = ${ownerId} for update
      `;
      const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ctrlb-durable-derivative-'));
      const engine = new DerivativeJobs({ ...this.options, directory });
      hydrate(engine, state.payload);
      const blobs = await sql`
        select job_id, bytes from ctrlb_private.derivative_blobs where owner_id = ${ownerId}
      `;
      for (const blob of blobs) fs.writeFileSync(engine.mediaPath(blob.job_id), Buffer.from(blob.bytes));
      try {
        const result = await operation(engine);
        const retained = [];
        for (const job of engine.jobs.values()) {
          const file = engine.mediaPath(job.id);
          if (!fs.existsSync(file)) continue;
          retained.push(job.id);
          await sql`
            insert into ctrlb_private.derivative_blobs (job_id, owner_id, bytes)
            values (${job.id}, ${ownerId}, ${fs.readFileSync(file)})
            on conflict (job_id) do update set bytes = excluded.bytes, updated_at = now()
          `;
        }
        if (retained.length) {
          await sql`
            delete from ctrlb_private.derivative_blobs
            where owner_id = ${ownerId} and job_id not in ${sql(retained)}
          `;
        } else {
          await sql`delete from ctrlb_private.derivative_blobs where owner_id = ${ownerId}`;
        }
        await sql`
          update ctrlb_private.derivative_owner_state
          set payload = ${sql.json(payloadFor(engine))},
              state_version = state_version + 1,
              updated_at = now()
          where owner_id = ${ownerId} and state_version = ${state.state_version}
        `;
        return result;
      } finally {
        fs.rmSync(directory, { recursive: true, force: true });
      }
    });
  }

  authorize(principal, request) { return this.withOwner(principal.id, (engine) => engine.authorize(principal, request)); }
  read(id, principal, context) { return this.withOwner(principal.id, (engine) => engine.read(id, principal, context)); }
  append(id, principal, offset, input, context) {
    return this.withOwner(principal.id, (engine) => engine.append(id, principal, offset, input, context));
  }
  complete(id, principal, declaration, context) {
    return this.withOwner(principal.id, (engine) => engine.complete(id, principal, declaration, context));
  }
  readCompletedBytes(id, principal, context) {
    return this.withOwner(principal.id, (engine) => engine.readCompletedBytes(id, principal, context));
  }
  setProviderFile(id, principal, value, context) {
    return this.withOwner(principal.id, (engine) => engine.setProviderFile(id, principal, value, context));
  }
  cancel(id, principal, context) { return this.withOwner(principal.id, (engine) => engine.cancel(id, principal, context)); }
  delete(id, principal, context) { return this.withOwner(principal.id, (engine) => engine.delete(id, principal, context)); }
  listActivity(principal, projectId) {
    return this.withOwner(principal.id, (engine) => engine.listActivity(principal, projectId));
  }
  cleanupOwner(ownerId) {
    return this.withOwner(ownerId, (engine) => engine.cleanup());
  }
  async cleanup() {
    const owners = await this.sql`select owner_id from ctrlb_private.derivative_owner_state`;
    await Promise.all(owners.map(({ owner_id: ownerId }) => this.cleanupOwner(ownerId)));
  }
  async deleteOwner(ownerId) {
    await this.sql.begin(async (sql) => {
      await sql`delete from ctrlb_private.derivative_blobs where owner_id = ${ownerId}`;
      await sql`delete from ctrlb_private.derivative_owner_state where owner_id = ${ownerId}`;
    });
  }
}

module.exports = { PostgresDerivativeJobs, hydrate, payloadFor };
