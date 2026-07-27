'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Readable } = require('node:stream');
const { DerivativeJobs } = require('../server/derivative-jobs.cjs');

const principal = Object.freeze({ id: 'user-a', scopes: ['derivatives:create'], authentication: 'oidc' });
const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(20)]);
const mp3 = Buffer.concat([Buffer.from('ID3'), Buffer.alloc(20)]);
const video = Buffer.concat([Buffer.alloc(4), Buffer.from('ftyp'), Buffer.from('isom'), Buffer.alloc(20)]);

function fixture(options = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ctrlb-derivative-test-'));
  let now = 1_800_000_000_000;
  const access = options.access || {
    entitlements: ['cloud_transcription', 'cloud_planning'],
    quotas: { concurrentJobs: 2, transcriptionMinutesPerMonth: 10, derivativeCostMicrosPerMonth: 1000 },
  };
  const jobs = new DerivativeJobs({ directory, now: () => now, ttlMs: 1000, retentionMs: 2000, accessFor: async () => access });
  return { directory, jobs, advance: (ms) => { now += ms; } };
}

function authorization(overrides = {}) {
  return {
    projectId: 'project-1', deviceSessionId: 'device-session-1', purpose: 'transcription',
    type: 'audio', acceptedFormat: 'audio/mpeg', maximumBytes: 64, maximumDurationMs: 60_000,
    maximumCount: 1, estimatedCostMicros: 10, idempotencyKey: cryptoKey(),
    sourceFingerprint: 'a'.repeat(64), timelineReferences: [{ sourceId: 'clip-1', startMs: 0, endMs: 1000 }],
    ...overrides,
  };
}
let key = 0;
function cryptoKey() { key += 1; return `request-${key}`; }

test('authorization binds the complete scope and makes retries idempotent', async (t) => {
  const { directory, jobs } = fixture(); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const request = authorization({ idempotencyKey: 'same-key' });
  const first = await jobs.authorize(principal, request);
  const retry = await jobs.authorize(principal, request);
  assert.equal(retry.job.id, first.job.id);
  assert.equal(retry.reused, true);
  assert.deepEqual(
    ['userId', 'projectId', 'deviceSessionId', 'purpose', 'type', 'acceptedFormat', 'maximumBytes', 'maximumDurationMs', 'maximumCount', 'idempotencyKey']
      .map((field) => first.job[field]),
    ['user-a', 'project-1', 'device-session-1', 'transcription', 'audio', 'audio/mpeg', 64, 60_000, 1, 'same-key'],
  );
});

test('entitlements, monthly quotas, concurrency, and cost ceilings are enforced before bytes', async (t) => {
  const { directory, jobs } = fixture({ access: {
    entitlements: ['cloud_transcription'], quotas: { concurrentJobs: 1, transcriptionMinutesPerMonth: 1, derivativeCostMicrosPerMonth: 10 },
  } }); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  await jobs.authorize(principal, authorization({ estimatedCostMicros: 10 }));
  await assert.rejects(jobs.authorize(principal, authorization()), (error) => error.code === 'concurrency_limit');
  const denied = fixture({ access: { entitlements: [], quotas: { concurrentJobs: 1, transcriptionMinutesPerMonth: 1 } } });
  t.after(() => fs.rmSync(denied.directory, { recursive: true, force: true }));
  await assert.rejects(denied.jobs.authorize(principal, authorization()), (error) => error.code === 'entitlement_required');
});

test('actual content is sniffed and video, spoofed types, oversize, cross-user, and replay are rejected', async (t) => {
  const { directory, jobs } = fixture(); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const job = (await jobs.authorize(principal, authorization())).job;
  await jobs.append(job.id, principal, 0, Readable.from(video));
  assert.throws(() => jobs.complete(job.id, principal, { durationMs: 1000 }), (error) => error.code === 'source_video_forbidden');
  assert.throws(() => jobs.read(job.id, { ...principal, id: 'user-b' }), (error) => error.code === 'not_found');
  assert.throws(() => jobs.read(job.id, principal, { projectId: 'project-2', deviceSessionId: 'device-session-1' }), (error) => error.code === 'not_found');
  jobs.cancel(job.id, principal);
  const small = (await jobs.authorize(principal, authorization({ maximumBytes: 4 }))).job;
  await assert.rejects(jobs.append(small.id, principal, 0, Readable.from(mp3)), (error) => error.code === 'byte_limit_exceeded');
  assert.equal(jobs.read(small.id, principal).offset, 0);
  const good = (await jobs.authorize(principal, authorization())).job;
  await jobs.append(good.id, principal, 0, Readable.from(mp3));
  jobs.complete(good.id, principal, { durationMs: 1000 });
  assert.throws(() => jobs.complete(good.id, principal), (error) => error.code === 'job_closed');
});

test('content keys reuse results, preserve provenance, delete bytes, and produce activity disclosure', async (t) => {
  const { directory, jobs } = fixture({ access: {
    entitlements: ['cloud_planning'], quotas: { concurrentJobs: 3, transcriptionMinutesPerMonth: 10, derivativeCostMicrosPerMonth: 1000 },
  } }); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const request = authorization({ type: 'frame', purpose: 'visual-analysis', acceptedFormat: 'image/jpeg', maximumDurationMs: null });
  const first = (await jobs.authorize(principal, request)).job;
  await jobs.append(first.id, principal, 0, Readable.from(jpeg));
  assert.equal(jobs.complete(first.id, principal).reused, false);
  const result = jobs.preserveResult(first.id, principal, { labels: ['screen'] });
  assert.equal(result.provenance.timelineReferences[0].sourceId, 'clip-1');
  assert.equal(fs.existsSync(jobs.mediaPath(first.id)), false);
  const second = (await jobs.authorize(principal, { ...request, idempotencyKey: cryptoKey() })).job;
  await jobs.append(second.id, principal, 0, Readable.from(jpeg));
  const reused = jobs.complete(second.id, principal);
  assert.equal(reused.reused, true);
  assert.deepEqual(reused.result.value, { labels: ['screen'] });
  assert.deepEqual(jobs.listActivity(principal, 'project-1').map((entry) => entry.action),
    ['authorized', 'transferred', 'result_preserved', 'authorized', 'reused']);
});

test('expiry, cancellation, and retention cleanup remove temporary derivatives', async (t) => {
  const { directory, jobs, advance } = fixture(); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const cancelled = (await jobs.authorize(principal, authorization())).job;
  jobs.cancel(cancelled.id, principal);
  assert.equal(fs.existsSync(jobs.mediaPath(cancelled.id)), false);
  const expired = (await jobs.authorize(principal, authorization())).job;
  advance(1001);
  assert.throws(() => jobs.read(expired.id, principal), (error) => error.code === 'job_expired');
  advance(1000);
  jobs.cleanup();
  assert.equal(jobs.jobs.size, 0);
});
