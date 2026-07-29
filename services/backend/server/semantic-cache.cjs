'use strict';

const crypto = require('node:crypto');
const postgres = require('postgres');

const CACHE_TYPES = new Set(['provider_context', 'media_index']);

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
}

function identity(input) {
  if (!CACHE_TYPES.has(input.cacheType)) throw new TypeError('Unsupported semantic cache type.');
  if (!Number.isSafeInteger(input.rangeStartMs) || input.rangeStartMs < 0 ||
      !Number.isSafeInteger(input.rangeEndMs) || input.rangeEndMs <= input.rangeStartMs) {
    throw new TypeError('Invalid semantic cache range.');
  }
  const transform = canonical(input.transform || {});
  const fields = [
    'ownerId', 'projectId', 'cacheType', 'sourceFingerprint',
    'model', 'modelVersion', 'analyzerVersion',
  ];
  for (const field of fields) if (typeof input[field] !== 'string' || !input[field]) throw new TypeError(`Invalid ${field}.`);
  return {
    ...input,
    transform,
    key: crypto.createHash('sha256').update(JSON.stringify({
      ...Object.fromEntries(fields.map((field) => [field, input[field]])),
      rangeStartMs: input.rangeStartMs,
      rangeEndMs: input.rangeEndMs,
      transform,
    })).digest('hex'),
  };
}

function sameIdentity(entry, query) {
  return entry.ownerId === query.ownerId && entry.projectId === query.projectId &&
    entry.cacheType === query.cacheType && entry.sourceFingerprint === query.sourceFingerprint &&
    entry.rangeStartMs === query.rangeStartMs && entry.rangeEndMs === query.rangeEndMs &&
    JSON.stringify(entry.transform) === JSON.stringify(query.transform) &&
    entry.model === query.model && entry.modelVersion === query.modelVersion &&
    entry.analyzerVersion === query.analyzerVersion;
}

class VolatileSemanticCache {
  constructor({ records, now = Date.now } = {}) {
    this.records = records || new Map();
    this.now = now;
  }
  async put(input) {
    const item = identity(input);
    const stored = {
      ...item, value: structuredClone(input.value), provenance: structuredClone(input.provenance),
      costMicros: input.costMicros || 0, hitCount: 0, createdAt: this.now(),
      expiresAt: input.expiresAt, lastHitAt: null,
    };
    this.records.set(item.key, stored);
    return structuredClone(stored);
  }
  async get(input) {
    const query = identity(input);
    const entry = this.records.get(query.key);
    if (!entry || !sameIdentity(entry, query) || entry.expiresAt <= this.now()) return null;
    entry.hitCount += 1;
    entry.lastHitAt = this.now();
    return structuredClone(entry);
  }
  async invalidateRange({ ownerId, projectId, sourceFingerprint, rangeStartMs, rangeEndMs }) {
    let removed = 0;
    for (const [key, entry] of this.records) {
      if (entry.ownerId === ownerId && entry.projectId === projectId &&
          entry.sourceFingerprint === sourceFingerprint &&
          entry.rangeStartMs < rangeEndMs && entry.rangeEndMs > rangeStartMs) {
        this.records.delete(key); removed += 1;
      }
    }
    return removed;
  }
  async deleteOwner(ownerId) {
    let removed = 0;
    for (const [key, entry] of this.records) if (entry.ownerId === ownerId) {
      this.records.delete(key); removed += 1;
    }
    return removed;
  }
  async cleanupExpired() {
    let removed = 0;
    for (const [key, entry] of this.records) if (entry.expiresAt <= this.now()) {
      this.records.delete(key); removed += 1;
    }
    return removed;
  }
}

class PostgresSemanticCache {
  constructor({ databaseUrl, sql } = {}) {
    if (!sql && !databaseUrl) throw new TypeError('Semantic cache database is not configured.');
    this.sql = sql || postgres(databaseUrl, {
      max: 4, idle_timeout: 20, connect_timeout: 10, prepare: false, ssl: 'require',
    });
  }
  async put(input) {
    const item = identity(input);
    const [row] = await this.sql`
      insert into ctrlb_private.semantic_cache_entries (
        owner_id, project_id, cache_type, source_fingerprint, range_start_ms, range_end_ms,
        transform, model, model_version, analyzer_version, value, provenance, cost_micros, expires_at
      ) values (
        ${item.ownerId}, ${item.projectId}, ${item.cacheType}, ${item.sourceFingerprint},
        ${item.rangeStartMs}, ${item.rangeEndMs}, ${this.sql.json(item.transform)},
        ${item.model}, ${item.modelVersion}, ${item.analyzerVersion},
        ${this.sql.json(item.value)}, ${this.sql.json(item.provenance)},
        ${item.costMicros || 0}, ${new Date(item.expiresAt)}
      )
      on conflict (
        owner_id, project_id, cache_type, source_fingerprint, range_start_ms, range_end_ms,
        transform, model, model_version, analyzer_version
      ) do update set value = excluded.value, provenance = excluded.provenance,
        cost_micros = excluded.cost_micros, expires_at = excluded.expires_at,
        hit_count = 0, last_hit_at = null, created_at = now()
      returning *
    `;
    return row;
  }
  async get(input) {
    const item = identity(input);
    const [row] = await this.sql`
      update ctrlb_private.semantic_cache_entries set
        hit_count = hit_count + 1, last_hit_at = now()
      where owner_id = ${item.ownerId} and project_id = ${item.projectId}
        and cache_type = ${item.cacheType} and source_fingerprint = ${item.sourceFingerprint}
        and range_start_ms = ${item.rangeStartMs} and range_end_ms = ${item.rangeEndMs}
        and transform = ${this.sql.json(item.transform)}
        and model = ${item.model} and model_version = ${item.modelVersion}
        and analyzer_version = ${item.analyzerVersion} and expires_at > now()
      returning *
    `;
    return row || null;
  }
  async invalidateRange({ ownerId, projectId, sourceFingerprint, rangeStartMs, rangeEndMs }) {
    const rows = await this.sql`
      delete from ctrlb_private.semantic_cache_entries
      where owner_id = ${ownerId} and project_id = ${projectId}
        and source_fingerprint = ${sourceFingerprint}
        and range_start_ms < ${rangeEndMs} and range_end_ms > ${rangeStartMs}
      returning id
    `;
    return rows.length;
  }
  async deleteOwner(ownerId) {
    const rows = await this.sql`
      delete from ctrlb_private.semantic_cache_entries where owner_id = ${ownerId} returning id
    `;
    return rows.length;
  }
  async cleanupExpired() {
    const rows = await this.sql`
      delete from ctrlb_private.semantic_cache_entries where expires_at <= now() returning id
    `;
    return rows.length;
  }
}

module.exports = { CACHE_TYPES, PostgresSemanticCache, VolatileSemanticCache, identity };
