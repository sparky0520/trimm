'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Transform } = require('node:stream');
const { requireAuthentication, requireOwner } = require('./authorization.cjs');

const TYPES = Object.freeze({
  video: Object.freeze(new Set(['video/mp4'])),
});
const PURPOSES = Object.freeze({
  video: Object.freeze(new Set(['video-inspection'])),
});
const TERMINAL = new Set(['completed', 'cancelled', 'expired', 'deleted']);
const SELECTION_REASONS = new Set([
  'request_selected', 'agent_requested', 'representative_coverage',
  'measured_candidate', 'uncertainty',
]);
const DEFAULT_TTL_MS = 15 * 60 * 1000;
const DEFAULT_RETENTION_MS = 6 * 60 * 60 * 1000;

function jobError(message, status = 400, code = 'invalid_derivative_job') {
  return Object.assign(new Error(message), { status, code });
}

function boundedString(value, name, pattern = /^[a-zA-Z0-9._:-]{1,200}$/) {
  if (typeof value !== 'string' || !pattern.test(value)) throw jobError(`Invalid ${name}.`);
  return value;
}

function positiveInteger(value, name, ceiling = Number.MAX_SAFE_INTEGER) {
  if (!Number.isSafeInteger(value) || value <= 0 || value > ceiling) throw jobError(`Invalid ${name}.`);
  return value;
}

function nonNegativeInteger(value, name, ceiling = Number.MAX_SAFE_INTEGER) {
  if (!Number.isSafeInteger(value) || value < 0 || value > ceiling) throw jobError(`Invalid ${name}.`);
  return value;
}

function timestamp(value, name) {
  if (typeof value !== 'string' || !Number.isFinite(Date.parse(value))) throw jobError(`Invalid ${name}.`);
  return new Date(value).toISOString();
}

function providerFile(value, now) {
  if (!value || value.provider !== 'google-gemini' || typeof value.uri !== 'string' ||
      !/^https:\/\/generativelanguage\.googleapis\.com\//.test(value.uri) ||
      typeof value.name !== 'string' || !/^files\/[A-Za-z0-9_-]+$/.test(value.name) ||
      !Number.isFinite(value.expiresAt) || value.expiresAt <= now || value.expiresAt > now + 7 * 24 * 60 * 60 * 1000) {
    throw jobError('Invalid provider file reference.', 400, 'invalid_provider_file');
  }
  return Object.freeze({ provider: value.provider, uri: value.uri, name: value.name, expiresAt: value.expiresAt });
}

function timelineReferences(value) {
  if (!Array.isArray(value) || !value.length || value.length > 100) {
    throw jobError('At least one exact timeline reference is required.', 400, 'timeline_mapping_required');
  }
  return value.map((reference) => {
    const sourceId = boundedString(reference?.sourceId, 'timeline source ID');
    const startMs = Number(reference?.startMs);
    const endMs = Number(reference?.endMs);
    if (!Number.isSafeInteger(startMs) || startMs < 0 || !Number.isSafeInteger(endMs) || endMs <= startMs) {
      throw jobError('Invalid timeline reference.', 400, 'invalid_timeline_mapping');
    }
    return Object.freeze({ sourceId, startMs, endMs });
  });
}

function providerDisclosures(value, derivativeType) {
  if (!Array.isArray(value) || value.length > 10) throw jobError('Provider disclosures are required.');
  return value.map((disclosure) => {
    const provider = boundedString(disclosure?.provider, 'provider');
    const purpose = boundedString(disclosure?.purpose, 'provider purpose');
    if (!Array.isArray(disclosure?.dataClasses) || !disclosure.dataClasses.length ||
        disclosure.dataClasses.some((item) => !TYPES[item]) || !disclosure.dataClasses.includes(derivativeType)) {
      throw jobError('Provider disclosure does not cover the derivative type.', 400, 'provider_disclosure_incomplete');
    }
    return Object.freeze({ provider, purpose, dataClasses: Object.freeze([...new Set(disclosure.dataClasses)]) });
  });
}

function detectedType(buffer) {
  if (buffer.length >= 12 && buffer.subarray(4, 8).toString('ascii') === 'ftyp') {
    const brand = buffer.subarray(8, 12).toString('ascii');
    return /^(M4A |M4B |mp4a)$/i.test(brand) ? 'audio/mp4' : 'video/mp4';
  }
  if (buffer.subarray(0, 3).equals(Buffer.from([0xff, 0xd8, 0xff]))) return 'image/jpeg';
  if (buffer.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'image/png';
  if (buffer.subarray(0, 4).toString('ascii') === 'RIFF') {
    if (buffer.subarray(8, 12).toString('ascii') === 'WAVE') return 'audio/wav';
    if (buffer.subarray(8, 12).toString('ascii') === 'WEBP') return 'image/webp';
  }
  if (buffer.subarray(0, 4).toString('ascii') === 'OggS') return 'audio/ogg';
  if (buffer.subarray(0, 3).toString('hex') === '494433' || (buffer[0] === 0xff && (buffer[1] & 0xe0) === 0xe0)) return 'audio/mpeg';
  if (buffer.subarray(0, 4).toString('hex') === '1a45dfa3') return 'webm';
  return null;
}

function detectedMp4TrackType(file) {
  const descriptor = fs.openSync(file, 'r');
  const chunk = Buffer.alloc(64 * 1024);
  let carry = Buffer.alloc(0);
  let hasAudio = false;
  try {
    for (;;) {
      const read = fs.readSync(descriptor, chunk, 0, chunk.length, null);
      if (!read) break;
      const sample = Buffer.concat([carry, chunk.subarray(0, read)]);
      let offset = 0;
      for (;;) {
        const marker = sample.indexOf('hdlr', offset, 'ascii');
        if (marker < 0) break;
        const handlerOffset = marker + 12;
        if (handlerOffset + 4 <= sample.length) {
          const handler = sample.subarray(handlerOffset, handlerOffset + 4).toString('ascii');
          if (handler === 'vide') return 'video/mp4';
          if (handler === 'soun') hasAudio = true;
        }
        offset = marker + 4;
      }
      carry = sample.subarray(Math.max(0, sample.length - 16));
    }
  } finally {
    fs.closeSync(descriptor);
  }
  return hasAudio ? 'audio/mp4' : null;
}

function validateActualContent(file, job) {
  const head = Buffer.alloc(32);
  const descriptor = fs.openSync(file, 'r');
  const read = fs.readSync(descriptor, head, 0, head.length, 0);
  fs.closeSync(descriptor);
  const sample = head.subarray(0, read);
  let actual = detectedType(sample);
  if (sample.length >= 12 && sample.subarray(4, 8).toString('ascii') === 'ftyp') {
    actual = detectedMp4TrackType(file) || actual;
  }
  if (actual === 'webm') actual = job.acceptedFormat === 'audio/webm' ? 'audio/webm' : 'video/webm';
  if (actual !== job.acceptedFormat) throw jobError('Derivative media type does not match its authorization.', 415, 'media_type_mismatch');
  return actual;
}

class DerivativeJobs {
  constructor(options = {}) {
    this.directory = options.directory || path.join(os.tmpdir(), 'ctrlb-derivative-jobs');
    this.now = options.now || Date.now;
    this.ttlMs = options.ttlMs || DEFAULT_TTL_MS;
    this.retentionMs = options.retentionMs || DEFAULT_RETENTION_MS;
    this.accessFor = options.accessFor || (async () => ({ entitlements: ['cloud_planning'], quotas: { concurrentJobs: 4, transcriptionMinutesPerMonth: 30 } }));
    this.jobs = new Map();
    this.idempotency = new Map();
    this.activity = new Map();
    this.monthlyUsage = new Map();
    this.activeWrites = new Set();
    fs.mkdirSync(this.directory, { recursive: true });
  }

  mediaPath(id) { return path.join(this.directory, `${id}.derivative`); }
  monthKey(userId) { return `${userId}:${new Date(this.now()).toISOString().slice(0, 7)}`; }
  activeCount(userId) {
    return [...this.jobs.values()].filter((job) => job.userId === userId && !TERMINAL.has(job.status) && job.expiresAt > this.now()).length;
  }
  log(job, action, detail = {}) {
    const entries = this.activity.get(job.userId) || [];
    entries.push(Object.freeze({
      timestamp: new Date(this.now()).toISOString(), jobId: job.id, projectId: job.projectId,
      purpose: job.purpose, derivativeType: job.type, action, ...detail,
    }));
    this.activity.set(job.userId, entries.slice(-500));
  }

  async authorize(principal, request) {
    requireAuthentication(principal);
    if (!request || typeof request !== 'object') throw jobError('Invalid derivative authorization.');
    const projectId = boundedString(request.projectId, 'project ID');
    const deviceSessionId = boundedString(request.deviceSessionId, 'device session ID');
    const purpose = boundedString(request.purpose, 'purpose');
    const type = boundedString(request.type, 'derivative type');
    const acceptedFormat = boundedString(request.acceptedFormat, 'accepted format', /^[a-z0-9.+-]+\/[a-z0-9.+-]+$/);
    const idempotencyKey = boundedString(request.idempotencyKey, 'idempotency key');
    if (request.mediaMode !== 'video_analysis' || type !== 'video') throw jobError('Only authorized video analysis uploads are accepted.', 400, 'video_analysis_required');
    const consentMode = request.consent?.mode;
    if (consentMode !== 'video_analysis') throw jobError('Video analysis consent is required.', 400, 'consent_required');
    const consent = Object.freeze({
      mode: consentMode,
      grantedAt: timestamp(request.consent.grantedAt, 'consent timestamp'),
      disclosureVersion: boundedString(request.consent.disclosureVersion, 'disclosure version'),
    });
    const selectionReason = boundedString(request.selectionReason, 'selection reason');
    if (!SELECTION_REASONS.has(selectionReason)) throw jobError('Unsupported selection reason.', 400, 'selection_reason_not_allowed');
    if (!TYPES[type]?.has(acceptedFormat)) throw jobError('Derivative format is not allowed for this type.', 415, 'format_not_allowed');
    if (!PURPOSES[type]?.has(purpose)) throw jobError('Purpose is not allowed for this derivative type.', 400, 'purpose_not_allowed');
    const maximumBytes = positiveInteger(request.maximumBytes, 'maximum bytes', 100 * 1024 * 1024);
    const maximumDurationMs = request.maximumDurationMs == null ? null : positiveInteger(request.maximumDurationMs, 'maximum duration', 3 * 60 * 60 * 1000);
    const maximumCount = request.maximumCount == null ? 1 : positiveInteger(request.maximumCount, 'maximum count', 100);
    const estimatedCostMicros = request.estimatedCostMicros == null
      ? 0
      : nonNegativeInteger(request.estimatedCostMicros, 'estimated cost', 100_000_000);
    const retentionMs = positiveInteger(request.retentionMs ?? this.retentionMs, 'retention', 24 * 60 * 60 * 1000);
    const resolution = request.resolution;
    if (!['LOW', 'HIGH'].includes(resolution)) throw jobError('Video resolution is required.', 400, 'invalid_video_resolution');
    const mapping = timelineReferences(request.timelineReferences);
    const disclosures = providerDisclosures(request.providerDisclosures, type);
    const sourceFingerprint = boundedString(request.sourceFingerprint, 'source fingerprint', /^[a-f0-9]{32,128}$/);
    const authorizationFingerprint = crypto.createHash('sha256').update(JSON.stringify({
      projectId, deviceSessionId, purpose, type, acceptedFormat, maximumBytes,
      maximumDurationMs, maximumCount, estimatedCostMicros, mediaMode: request.mediaMode,
      consent, selectionReason, providerDisclosures: disclosures, sourceFingerprint,
      timelineReferences: mapping, retentionMs, resolution,
    })).digest('hex');
    if (maximumDurationMs == null || maximumDurationMs > 30_000) throw jobError('Video jobs require a duration no greater than 30 seconds.');
    const access = await this.accessFor(principal.id);
    const entitlement = 'cloud_planning';
    if (!access.entitlements?.includes(entitlement)) throw jobError('Entitlement required.', 403, 'entitlement_required');
    if (this.activeCount(principal.id) >= access.quotas.concurrentJobs) throw jobError('Concurrent job limit reached.', 429, 'concurrency_limit');
    const durationMinutes = 0;
    const usageKey = this.monthKey(principal.id);
    const usage = this.monthlyUsage.get(usageKey) || { transcriptionMinutes: 0, estimatedCostMicros: 0 };
    if (durationMinutes && usage.transcriptionMinutes + durationMinutes > access.quotas.transcriptionMinutesPerMonth) {
    const usageKey = this.monthKey(principal.id);
    const usage = this.monthlyUsage.get(usageKey) || { transcriptionMinutes: 0, estimatedCostMicros: 0 };
    if (durationMinutes && usage.transcriptionMinutes + durationMinutes > access.quotas.transcriptionMinutesPerMonth) {
      throw jobError('Monthly derivative quota exceeded.', 429, 'quota_exceeded');
    }
    const costCeiling = Number.isSafeInteger(access.quotas.derivativeCostMicrosPerMonth)
      ? access.quotas.derivativeCostMicrosPerMonth : 100_000_000;
    if (usage.estimatedCostMicros + estimatedCostMicros > costCeiling) throw jobError('Derivative cost ceiling exceeded.', 429, 'cost_ceiling_exceeded');
    const idemKey = `${principal.id}:${projectId}:${idempotencyKey}`;
    const existingId = this.idempotency.get(idemKey);
    if (existingId) {
      const existing = this.jobs.get(existingId);
      if (existing && existing.expiresAt > this.now()) {
        if (existing.authorizationFingerprint !== authorizationFingerprint) {
          throw jobError('Idempotency key was already used for different derivative authorization.', 409, 'idempotency_conflict');
        }
        if (existing.status === 'completed' && fs.existsSync(this.mediaPath(existing.id))) {
          return { job: existing, reused: true };
        }
      }
    }
    const id = crypto.randomUUID();
    const job = {
      id, userId: principal.id, projectId, deviceSessionId, purpose, type, acceptedFormat,
      maximumBytes, maximumDurationMs, maximumCount, estimatedCostMicros, idempotencyKey,
      mediaMode: request.mediaMode, consent, selectionReason, providerDisclosures: disclosures, resolution,
      sourceFingerprint, timelineReferences: mapping, retentionMs, authorizationFingerprint,
      offset: 0, status: 'authorized', createdAt: this.now(), expiresAt: this.now() + this.ttlMs,
      deleteAt: this.now() + retentionMs, contentKey: null, result: null,
      providerFile: null,
    };
    this.jobs.set(id, job);
    this.idempotency.set(idemKey, id);
    this.monthlyUsage.set(usageKey, {
      transcriptionMinutes: usage.transcriptionMinutes + durationMinutes,
      estimatedCostMicros: usage.estimatedCostMicros + estimatedCostMicros,
    });
    fs.writeFileSync(this.mediaPath(id), Buffer.alloc(0), { flag: 'wx' });
    this.log(job, 'authorized', {
      format: acceptedFormat, maximumBytes, maximumDurationMs, selectionReason,
      consentMode: consent.mode, retentionMs, providers: disclosures.map(({ provider }) => provider),
      timelineReferences: mapping,
    });
    return { job, reused: false };
  }

  read(id, principal, context) {
    requireAuthentication(principal);
    const job = this.jobs.get(id);
    if (!job) throw jobError('Derivative job not found.', 404, 'not_found');
    requireOwner(principal, job.userId);
    if (context && (context.projectId !== job.projectId || context.deviceSessionId !== job.deviceSessionId)) {
      throw jobError('Derivative job not found.', 404, 'not_found');
    }
    if (job.expiresAt <= this.now() && !TERMINAL.has(job.status)) {
      job.status = 'expired'; this.removeBytes(job); this.log(job, 'expired');
      throw jobError('Derivative job expired.', 410, 'job_expired');
    }
    return job;
  }

  async append(id, principal, expectedOffset, input, context) {
    const job = this.read(id, principal, context);
    if (!['authorized', 'uploading'].includes(job.status)) throw jobError('Derivative upload is closed.', 409, 'job_closed');
    if (Number(expectedOffset) !== job.offset) throw Object.assign(jobError('Upload offset does not match.', 409, 'offset_mismatch'), { offset: job.offset });
    if (this.activeWrites.has(id)) throw jobError('Derivative is already being updated.', 409, 'concurrent_write');
    this.activeWrites.add(id);
    let received = 0;
    const remaining = job.maximumBytes - job.offset;
    const limiter = new Transform({ transform(chunk, _encoding, callback) {
      received += chunk.length;
      callback(received > remaining ? jobError('Derivative exceeds its authorized byte limit.', 413, 'byte_limit_exceeded') : null, chunk);
    } });
    try {
      await new Promise((resolve, reject) => {
        const output = fs.createWriteStream(this.mediaPath(id), { flags: 'r+', start: job.offset });
        let settled = false;
        const finish = (error) => { if (settled) return; settled = true; error ? reject(error) : resolve(); };
        input.on('error', finish); limiter.on('error', finish); output.on('error', finish); output.on('finish', () => finish());
        input.pipe(limiter).pipe(output);
      });
      job.offset += received; job.status = 'uploading';
      return job;
    } catch (error) {
      try { fs.truncateSync(this.mediaPath(id), job.offset); } catch {}
      throw error;
    } finally { this.activeWrites.delete(id); }
  }

  complete(id, principal, declaration = {}, context) {
    const job = this.read(id, principal, context);
    if (!['authorized', 'uploading'].includes(job.status)) throw jobError('Derivative upload is closed.', 409, 'job_closed');
    if (job.offset <= 0) throw jobError('Derivative upload is empty.', 409, 'empty_derivative');
    if (declaration.durationMs != null && (!job.maximumDurationMs || declaration.durationMs > job.maximumDurationMs)) {
      throw jobError('Derivative exceeds its authorized duration.', 413, 'duration_limit_exceeded');
    }
    if (declaration.count != null && declaration.count > job.maximumCount) throw jobError('Derivative exceeds its authorized item count.', 413, 'count_limit_exceeded');
    return { job, reused: false };
  }

  read(id, principal, context) {
    requireAuthentication(principal);
    const job = this.jobs.get(id);
    if (!job) throw jobError('Derivative job not found.', 404, 'not_found');
    requireOwner(principal, job.userId);
    if (context && (context.projectId !== job.projectId || context.deviceSessionId !== job.deviceSessionId)) {
      throw jobError('Derivative job not found.', 404, 'not_found');
    }
    if (job.expiresAt <= this.now() && !TERMINAL.has(job.status)) {
      job.status = 'expired'; this.removeBytes(job); this.log(job, 'expired');
      throw jobError('Derivative job expired.', 410, 'job_expired');
    }
    return job;
  }

  async append(id, principal, expectedOffset, input, context) {
    const job = this.read(id, principal, context);
    if (!['authorized', 'uploading'].includes(job.status)) throw jobError('Derivative upload is closed.', 409, 'job_closed');
    if (Number(expectedOffset) !== job.offset) throw Object.assign(jobError('Upload offset does not match.', 409, 'offset_mismatch'), { offset: job.offset });
    if (this.activeWrites.has(id)) throw jobError('Derivative is already being updated.', 409, 'concurrent_write');
    this.activeWrites.add(id);
    let received = 0;
    const remaining = job.maximumBytes - job.offset;
    const limiter = new Transform({ transform(chunk, _encoding, callback) {
      received += chunk.length;
      callback(received > remaining ? jobError('Derivative exceeds its authorized byte limit.', 413, 'byte_limit_exceeded') : null, chunk);
    } });
    try {
      await new Promise((resolve, reject) => {
        const output = fs.createWriteStream(this.mediaPath(id), { flags: 'r+', start: job.offset });
        let settled = false;
        const finish = (error) => { if (settled) return; settled = true; error ? reject(error) : resolve(); };
        input.on('error', finish); limiter.on('error', finish); output.on('error', finish); output.on('finish', () => finish());
        input.pipe(limiter).pipe(output);
      });
      job.offset += received; job.status = 'uploading';
      return job;
    } catch (error) {
      try { fs.truncateSync(this.mediaPath(id), job.offset); } catch {}
      throw error;
    } finally { this.activeWrites.delete(id); }
  }

  complete(id, principal, declaration = {}, context) {
    const job = this.read(id, principal, context);
    if (!['authorized', 'uploading'].includes(job.status)) throw jobError('Derivative upload is closed.', 409, 'job_closed');
    if (job.offset <= 0) throw jobError('Derivative upload is empty.', 409, 'empty_derivative');
    if (declaration.durationMs != null && (!job.maximumDurationMs || declaration.durationMs > job.maximumDurationMs)) {
      throw jobError('Derivative exceeds its authorized duration.', 413, 'duration_limit_exceeded');
    }
    if (declaration.count != null && declaration.count > job.maximumCount) throw jobError('Derivative exceeds its authorized item count.', 413, 'count_limit_exceeded');
    validateActualContent(this.mediaPath(id), job);
    const digest = crypto.createHash('sha256').update(fs.readFileSync(this.mediaPath(id))).digest('hex');
    job.contentKey = crypto.createHash('sha256').update([
      job.userId, job.projectId, job.type, job.acceptedFormat, job.purpose, job.sourceFingerprint, digest,
    ].join(':')).digest('hex');
    const cached = this.results.get(job.contentKey);
    job.status = 'completed';
    job.completedAt = this.now();
    if (cached) {
      job.result = cached.result;
      job.resultReused = true;
      this.log(job, 'reused', { contentKey: job.contentKey });
      return { job, reused: true, result: cached.result };
    }
    job.resultReused = false;
    this.log(job, 'transferred', { bytes: job.offset, format: job.acceptedFormat });
    return { job, reused: false, openStream: () => fs.createReadStream(this.mediaPath(id)) };
  }

  preserveResult(id, principal, result) {
    const job = this.read(id, principal);
    if (job.status !== 'completed' || !job.contentKey) throw jobError('Derivative job is not complete.', 409, 'job_incomplete');
    const record = Object.freeze({
      value: result, provenance: Object.freeze({
        jobId: job.id, projectId: job.projectId, sourceFingerprint: job.sourceFingerprint,
        timelineReferences: job.timelineReferences, derivativeType: job.type, purpose: job.purpose,
      }),
    });
    job.result = record;
    this.results.set(job.contentKey, { result: record, createdAt: this.now() });
    this.log(job, 'result_preserved', { contentKey: job.contentKey });
    return record;
  }

  readCompletedBytes(id, principal, context) {
    const job = this.read(id, principal, context);
    if (job.status !== 'completed' || !job.contentKey) {
      throw jobError('Derivative job is not complete.', 409, 'job_incomplete');
    }
    return { job, bytes: fs.readFileSync(this.mediaPath(id)) };
  }

  setProviderFile(id, principal, value, context) {
    const job = this.read(id, principal, context);
    if (job.status !== 'completed') throw jobError('Derivative job is not complete.', 409, 'job_incomplete');
    job.providerFile = providerFile(value, this.now());
    this.log(job, 'provider_file_attached', { provider: job.providerFile.provider, expiresAt: new Date(job.providerFile.expiresAt).toISOString() });
    return job.providerFile;
  }

  removeBytes(job) { try { fs.unlinkSync(this.mediaPath(job.id)); } catch {} }
  cancel(id, principal, context) {
    const job = this.read(id, principal, context);
    if (!TERMINAL.has(job.status)) { job.status = 'cancelled'; this.removeBytes(job); this.log(job, 'cancelled'); }
  }
  delete(id, principal, context) {
    const job = this.read(id, principal, context);
    this.removeBytes(job);
    if (job.contentKey) this.results.delete(job.contentKey);
    job.result = null;
    job.status = 'deleted';
    job.deletedAt = this.now();
    this.log(job, 'deleted');
    return job;
  }
  listActivity(principal, projectId) {
    requireAuthentication(principal);
    return (this.activity.get(principal.id) || []).filter((entry) => !projectId || entry.projectId === projectId);
  }
  cleanup() {
    const now = this.now();
    for (const job of this.jobs.values()) {
      if (!TERMINAL.has(job.status) && job.expiresAt <= now) { job.status = 'expired'; this.removeBytes(job); this.log(job, 'expired'); }
      if (job.deleteAt <= now) { this.removeBytes(job); this.jobs.delete(job.id); }
    }
    for (const [key, cached] of this.results) if (cached.createdAt + this.retentionMs <= now) this.results.delete(key);
    const referenced = new Set([...this.jobs.keys()].map((id) => `${id}.derivative`));
    for (const name of fs.readdirSync(this.directory)) if (!referenced.has(name)) { try { fs.unlinkSync(path.join(this.directory, name)); } catch {} }
  }
}

module.exports = {
  DEFAULT_RETENTION_MS, DEFAULT_TTL_MS, DerivativeJobs, PURPOSES,
  SELECTION_REASONS, TYPES, detectedType, validateActualContent,
};
