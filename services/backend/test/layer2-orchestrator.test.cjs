'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Readable } = require('node:stream');
const { DerivativeJobs } = require('../server/derivative-jobs.cjs');
const { Layer2Orchestrator, validateDecision, validateInventory } = require('../server/layer2-orchestrator.cjs');

const principal = Object.freeze({ id: 'user-a', scopes: ['plan:create'], authentication: 'oidc' });
const inventory = Object.freeze({
  projectId: 'project-1',
  deviceSessionId: 'device-1',
  projectRevision: 4,
  intent: 'Make a concise product demo',
  sources: [{ id: 'clip-1', fingerprint: 'a'.repeat(64), durationMs: 60_000, width: 1920, height: 1080, hasAudio: true }],
  mediaIndex: {
    schemaVersion: 1, indexId: 'index-1', requestScope: 'broad_editorial', generatedAtEpochMs: 1,
    segments: [{ id: 'segment-1', sourceId: 'clip-1', startMs: 0, endMs: 10_000, signalKinds: ['speech'], observationIds: ['speech-1'] }],
    selections: [{ id: 'selection-1', sourceId: 'clip-1', startMs: 0, endMs: 10_000, reasons: ['representative_coverage'], observationIds: ['speech-1'], derivativeKinds: ['audio', 'frame'] }],
  },
  budgets: { maximumBytes: 1_000_000, maximumDurationMs: 30_000, maximumCostMicros: 1000, maximumRequests: 2, retentionMs: 60_000 },
  mediaConsent: {
    mode: 'selective_analysis',
    grantedAt: '2026-07-29T00:00:00.000Z',
    disclosureVersion: 'media-v1',
  },
  supportedContractVersions: {
    agentRuns: [1], activityEvents: [1], evidenceRequests: [1], editPrograms: [1],
  },
});

function fixture(decide) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ctrlb-layer2-test-'));
  const jobs = new DerivativeJobs({
    directory,
    accessFor: async () => ({
      entitlements: ['cloud_transcription', 'cloud_planning'],
      quotas: { concurrentJobs: 10, transcriptionMinutesPerMonth: 100, derivativeCostMicrosPerMonth: 10_000 },
    }),
  });
  return { directory, jobs, orchestrator: new Layer2Orchestrator({ derivativeJobs: jobs, decide }) };
}

test('compact inventories reject cloud-private fields and source media paths', () => {
  assert.equal(validateInventory(inventory).sources[0].id, 'clip-1');
  assert.throws(() => validateInventory({ ...inventory, tools: [{ name: 'ffmpeg' }] }), (error) => error.code === 'cloud_boundary_violation');
  assert.throws(() => validateInventory({ ...inventory, sources: [{ ...inventory.sources[0], sourceUri: 'file:///private/video.mp4' }] }),
    (error) => error.code === 'cloud_boundary_violation');
});

test('agent sessions require a mutually supported generated contract set', () => {
  assert.throws(() => validateInventory({
    ...inventory,
    supportedContractVersions: { ...inventory.supportedContractVersions, editPrograms: [2] },
  }), (error) => error.code === 'contract_version_not_supported' && error.status === 406);
});

test('media indexes stay request-shaped and cannot invent editorial labels', () => {
  assert.equal(validateInventory(inventory).mediaIndex.requestScope, 'broad_editorial');
  assert.throws(() => validateInventory({
    ...inventory,
    mediaIndex: {
      ...inventory.mediaIndex,
      segments: [{ ...inventory.mediaIndex.segments[0], label: 'failed take' }],
    },
  }), (error) => error.code === 'invalid_media_index');
  assert.throws(() => validateInventory({
    ...inventory,
    mediaIndex: {
      ...inventory.mediaIndex,
      selections: [{ ...inventory.mediaIndex.selections[0], endMs: 70_000 }],
    },
  }), (error) => error.code === 'invalid_media_index');
});

test('planner requests become scoped jobs only within byte, duration, cost, and count budgets', async (t) => {
  const setup = fixture(async () => ({
    type: 'evidence_request',
    requests: [{
      sourceId: 'clip-1', type: 'audio', purpose: 'transcription', acceptedFormat: 'audio/mpeg',
      startMs: 10_000, endMs: 30_000, maximumBytes: 500_000, maximumDurationMs: 20_000, estimatedCostMicros: 400,
    }],
  }));
  t.after(() => fs.rmSync(setup.directory, { recursive: true, force: true }));
  const response = await setup.orchestrator.orchestrate(principal, inventory);
  assert.equal(response.type, 'evidence_request');
  assert.deepEqual(response.jobs[0].range, { startMs: 10_000, endMs: 30_000 });
  const job = setup.jobs.jobs.get(response.jobs[0].id);
  assert.equal(job.userId, 'user-a');
  assert.equal(job.projectId, 'project-1');
  assert.equal(job.sourceFingerprint, 'a'.repeat(64));

  const overBudget = fixture(async () => ({
    type: 'evidence_request',
    requests: [{
      sourceId: 'clip-1', type: 'audio', purpose: 'transcription', acceptedFormat: 'audio/mpeg',
      startMs: 0, endMs: 60_000, maximumBytes: 1_000_001, maximumDurationMs: 60_000, estimatedCostMicros: 1001,
    }],
  }));
  t.after(() => fs.rmSync(overBudget.directory, { recursive: true, force: true }));
  await assert.rejects(overBudget.orchestrator.orchestrate(principal, inventory), (error) => error.code === 'invalid_orchestration' || error.code === 'evidence_budget_exceeded');
  assert.equal(overBudget.jobs.jobs.size, 0);
});

test('edit programs require completed owned evidence, valid citations, confidence, and current revision', async (t) => {
  let evidenceId;
  const setup = fixture(async ({ evidence }) => ({
    type: 'edit_program',
    expectedRevision: 4,
    summary: 'Remove the transcript-grounded fumble.',
    operations: [{
      id: 'op-1', type: 'remove_fumbles', enabled: true,
      ranges: [{ startMs: 1000, endMs: 1500 }], reasons: ['repeated phrase'],
      confidence: 0.96, evidenceIds: [evidence[0].id],
      speechDecision: {
        meaningPreserved: true,
        completeDeliverySelected: true,
        rationale: 'The alternate take completes the same thought with a continuous delivery.',
        contextEvidenceIds: [evidence[0].id],
        locatorSignals: ['repeated_phrase'],
        contextSignals: ['surrounding_context', 'speaker_scene_cadence', 'word_phoneme_timing', 'alternate_takes', 'user_intent', 'proposal_simulation'],
        transcriptUncertain: false,
        uncertaintyAction: 'none',
        technicalTieBreakerOnly: true,
      },
    }],
  }));
  t.after(() => fs.rmSync(setup.directory, { recursive: true, force: true }));
  const authorized = await setup.jobs.authorize(principal, {
    projectId: 'project-1', deviceSessionId: 'device-1', purpose: 'planning',
    type: 'evidence', acceptedFormat: 'application/json', maximumBytes: 1024,
    maximumCount: 1, estimatedCostMicros: 1, idempotencyKey: 'evidence-1',
    sourceFingerprint: 'a'.repeat(64), timelineReferences: [{ sourceId: 'clip-1', startMs: 0, endMs: 2000 }],
    mediaMode: 'selective_derivative', selectionReason: 'request_selected',
    retentionMs: 60_000, consent: inventory.mediaConsent,
    providerDisclosures: [{ provider: 'google-gemini', purpose: 'multimodal-planning', dataClasses: ['evidence'] }],
  });
  evidenceId = authorized.job.id;
  await setup.jobs.append(evidenceId, principal, 0, Readable.from(Buffer.from('{"words":[]}')));
  setup.jobs.complete(evidenceId, principal);
  setup.jobs.preserveResult(evidenceId, principal, { observations: [{ id: 'word-1', startMs: 1000 }] });

  const response = await setup.orchestrator.orchestrate(principal, { ...inventory, evidenceJobIds: [evidenceId] });
  assert.equal(response.operations[0].evidenceIds[0], evidenceId);
  assert.throws(() => validateDecision({
    ...response,
    operations: [{ ...response.operations[0], evidenceIds: ['00000000-0000-0000-0000-000000000000'] }],
  }, validateInventory(inventory), [{ id: evidenceId }]), (error) => error.code === 'invalid_evidence_reference');
  assert.throws(() => validateDecision({ ...response, expectedRevision: 3 }, validateInventory(inventory), [{ id: evidenceId }]),
    (error) => error.code === 'invalid_edit_program');
});

test('missing evidence yields bounded fallback or clarification without invented edits', async (t) => {
  const fallback = fixture(async () => ({ type: 'fallback', reason: 'No transcript evidence is available.' }));
  t.after(() => fs.rmSync(fallback.directory, { recursive: true, force: true }));
  assert.deepEqual(await fallback.orchestrator.orchestrate(principal, inventory),
    { type: 'fallback', reason: 'No transcript evidence is available.' });

  const invented = fixture(async () => ({
    type: 'edit_program', expectedRevision: 4, operations: [
      { id: 'op-1', type: 'set_audio_cleanup', enabled: true, confidence: 0.8, evidenceIds: ['invented'] },
    ],
  }));
  t.after(() => fs.rmSync(invented.directory, { recursive: true, force: true }));
  await assert.rejects(invented.orchestrator.orchestrate(principal, inventory), (error) => error.code === 'invalid_evidence_reference');
});

test('partial multi-job authorization is cancelled when a later job is denied', async (t) => {
  const setup = fixture(async () => ({
    type: 'evidence_request',
    requests: [
      {
        sourceId: 'clip-1', type: 'frame', purpose: 'visual-analysis', acceptedFormat: 'image/jpeg',
        startMs: 0, endMs: 1000, maximumBytes: 1000, maximumCount: 1, estimatedCostMicros: 400,
      },
      {
        sourceId: 'clip-1', type: 'frame', purpose: 'visual-analysis', acceptedFormat: 'image/jpeg',
        startMs: 1000, endMs: 2000, maximumBytes: 1000, maximumCount: 1, estimatedCostMicros: 400,
      },
    ],
  }));
  t.after(() => fs.rmSync(setup.directory, { recursive: true, force: true }));
  setup.jobs.accessFor = async () => ({
    entitlements: ['cloud_planning'],
    quotas: { concurrentJobs: 1, transcriptionMinutesPerMonth: 100, derivativeCostMicrosPerMonth: 10_000 },
  });
  await assert.rejects(setup.orchestrator.orchestrate(principal, {
    ...inventory,
    budgets: { ...inventory.budgets, maximumBytes: 5000 },
  }), (error) => error.code === 'concurrency_limit');
  assert.equal([...setup.jobs.jobs.values()][0].status, 'cancelled');
});
