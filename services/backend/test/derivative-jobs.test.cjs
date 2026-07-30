'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Readable } = require('node:stream');
const { DerivativeJobs } = require('../server/derivative-jobs.cjs');
const { hydrate, payloadFor } = require('../server/postgres-derivative-jobs.cjs');

const principal = Object.freeze({ id: 'user-a', scopes: ['derivatives:create'], authentication: 'oidc' });
const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(20)]);
const mp3 = Buffer.concat([Buffer.from('ID3'), Buffer.alloc(20)]);
const video = Buffer.concat([Buffer.alloc(4), Buffer.from('ftyp'), Buffer.from('isom'), Buffer.alloc(20)]);
const androidAudioMp4 = Buffer.concat([
  Buffer.alloc(4), Buffer.from('ftyp'), Buffer.from('mp42'), Buffer.alloc(24),
  Buffer.from('hdlr'), Buffer.alloc(8), Buffer.from('soun'), Buffer.alloc(20),
]);

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
    mediaMode: 'selective_derivative',
    selectionReason: 'request_selected',
    consent: {
      mode: 'selective_analysis',
      grantedAt: '2026-07-29T00:00:00.000Z',
      disclosureVersion: 'media-v1',
    },
    providerDisclosures: [{
      provider: 'google-gemini',
      purpose: 'multimodal-planning',
      dataClasses: ['audio'],
    }],
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
  await assert.rejects(
    jobs.authorize(principal, { ...request, selectionReason: 'uncertainty' }),
    (error) => error.code === 'idempotency_conflict',
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
  const request = authorization({
    type: 'frame', purpose: 'visual-analysis', acceptedFormat: 'image/jpeg', maximumDurationMs: null,
    providerDisclosures: [{ provider: 'google-gemini', purpose: 'multimodal-planning', dataClasses: ['frame'] }],
  });
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

test('generic Android MP4 brands are accepted when the container has only an audio track', async (t) => {
  const { directory, jobs } = fixture();
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const job = (await jobs.authorize(principal, authorization({
    acceptedFormat: 'audio/mp4',
    maximumBytes: 128,
  }))).job;
  await jobs.append(job.id, principal, 0, Readable.from(androidAudioMp4));
  assert.equal(jobs.complete(job.id, principal, { durationMs: 1000 }).job.status, 'completed');
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

test('zero estimated cost is accepted for provider-requested evidence', async (t) => {
  const { directory, jobs } = fixture();
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const result = await jobs.authorize(principal, authorization({ estimatedCostMicros: 0 }));
  assert.equal(result.job.estimatedCostMicros, 0);
});

test('authorization records consent, selection, disclosure, mapping, and retention', async (t) => {
  const { directory, jobs } = fixture(); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const job = (await jobs.authorize(principal, authorization({
    selectionReason: 'uncertainty',
    retentionMs: 1500,
  }))).job;
  assert.equal(job.mediaMode, 'selective_derivative');
  assert.equal(job.consent.mode, 'selective_analysis');
  assert.equal(job.selectionReason, 'uncertainty');
  assert.equal(job.providerDisclosures[0].provider, 'google-gemini');
  assert.deepEqual(job.timelineReferences, [{ sourceId: 'clip-1', startMs: 0, endMs: 1000 }]);
  assert.equal(job.deleteAt - job.createdAt, 1500);
  const activity = jobs.listActivity(principal, 'project-1')[0];
  assert.equal(activity.selectionReason, 'uncertainty');
  assert.deepEqual(activity.providers, ['google-gemini']);
});

test('derivative route rejects proxy/original modes and incomplete governance', async (t) => {
  const { directory, jobs } = fixture(); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  for (const mediaMode of ['optimized_proxy', 'original_source', undefined]) {
    await assert.rejects(
      jobs.authorize(principal, authorization({ mediaMode })),
      (error) => error.code === 'separate_upload_mode_required',
    );
  }
  await assert.rejects(
    jobs.authorize(principal, authorization({ timelineReferences: [] })),
    (error) => error.code === 'timeline_mapping_required',
  );
  await assert.rejects(
    jobs.authorize(principal, authorization({ consent: null })),
    (error) => error.code === 'consent_required',
  );
  await assert.rejects(
    jobs.authorize(principal, authorization({
      providerDisclosures: [{ provider: 'google-gemini', purpose: 'planning', dataClasses: ['frame'] }],
    })),
    (error) => error.code === 'provider_disclosure_incomplete',
  );
});

test('explicit deletion removes completed bytes, preserved results, and records a tombstone', async (t) => {
  const { directory, jobs } = fixture(); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const job = (await jobs.authorize(principal, authorization())).job;
  await jobs.append(job.id, principal, 0, Readable.from(mp3));
  jobs.complete(job.id, principal, { durationMs: 1000 });
  jobs.preserveResult(job.id, principal, { words: [] });
  const contentKey = job.contentKey;
  assert.equal(jobs.results.has(contentKey), true);
  const deleted = jobs.delete(job.id, principal);
  assert.equal(deleted.status, 'deleted');
  assert.equal(deleted.result, null);
  assert.equal(jobs.results.has(contentKey), false);
  assert.equal(fs.existsSync(jobs.mediaPath(job.id)), false);
  assert.equal(jobs.listActivity(principal, 'project-1').at(-1).action, 'deleted');
});

test('content reuse is partitioned by owner and project', async (t) => {
  const { directory, jobs } = fixture({ access: {
    entitlements: ['cloud_transcription', 'cloud_planning'],
    quotas: { concurrentJobs: 5, transcriptionMinutesPerMonth: 20, derivativeCostMicrosPerMonth: 1000 },
  } }); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const first = (await jobs.authorize(principal, authorization())).job;
  await jobs.append(first.id, principal, 0, Readable.from(mp3));
  jobs.complete(first.id, principal, { durationMs: 1000 });
  jobs.preserveResult(first.id, principal, { words: ['private'] });

  const other = { ...principal, id: 'user-b' };
  const second = (await jobs.authorize(other, authorization())).job;
  await jobs.append(second.id, other, 0, Readable.from(mp3));
  const completed = jobs.complete(second.id, other, { durationMs: 1000 });
  assert.equal(completed.reused, false);
  assert.notEqual(second.contentKey, first.contentKey);
});

test('serialized durable state resumes a partial upload after process restart', async (t) => {
  const first = fixture();
  const secondDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'ctrlb-derivative-restart-'));
  t.after(() => fs.rmSync(first.directory, { recursive: true, force: true }));
  t.after(() => fs.rmSync(secondDirectory, { recursive: true, force: true }));
  const job = (await first.jobs.authorize(principal, authorization())).job;
  await first.jobs.append(job.id, principal, 0, Readable.from(mp3.subarray(0, 8)));

  const restarted = new DerivativeJobs({
    directory: secondDirectory,
    now: first.jobs.now,
    ttlMs: first.jobs.ttlMs,
    retentionMs: first.jobs.retentionMs,
    accessFor: first.jobs.accessFor,
  });
  hydrate(restarted, structuredClone(payloadFor(first.jobs)));
  fs.copyFileSync(first.jobs.mediaPath(job.id), restarted.mediaPath(job.id));

  assert.equal(restarted.read(job.id, principal).offset, 8);
  await restarted.append(job.id, principal, 8, Readable.from(mp3.subarray(8)));
  assert.equal(restarted.complete(job.id, principal, { durationMs: 1000 }).job.status, 'completed');
});
