'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const plannerCore = require('../server/planner-core.cjs');

test('mobile planner is configured as a non-secret package script', () => {
  const packageJson = require('../../../package.json');
  assert.equal(packageJson.scripts['planner:serve'], 'node services/backend/server/mobile-planner.cjs');
  assert.equal(packageJson.scripts['backend:serve'], 'node services/backend/server/mobile-planner.cjs');
  const source = require('node:fs').readFileSync(require('node:path').join(__dirname, '..', 'server', 'mobile-planner.cjs'), 'utf8');
  assert.match(source, /process\.env\.MOONSHOT_API_KEY/);
  assert.match(source, /\/v1\/desktop\/plan/);
  assert.match(source, /\/v1\/mobile\/plan/);
  assert.match(source, /\/v1\/mobile\/orchestrate/);
  assert.match(source, /\/v1\/transcribe/);
  assert.match(source, /\/v1\/derivatives\/jobs/);
  assert.match(source, /\/v1\/derivatives\/activity/);
  assert.doesNotMatch(source, /sk-[a-zA-Z0-9]{20}/);
});

test('Render backend serves the public privacy policy without authentication', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const serverDirectory = path.join(__dirname, '..', 'server');
  const source = fs.readFileSync(path.join(serverDirectory, 'mobile-planner.cjs'), 'utf8');
  const policy = fs.readFileSync(path.join(serverDirectory, 'privacy.html'), 'utf8');

  assert.match(source, /requestUrl\.pathname === '\/privacy'/);
  assert.match(source, /return html\(response, 200, privacyPolicy\)/);
  assert.match(policy, /ctrlB Privacy Policy/);
  assert.match(policy, /darshjain05\.in@gmail\.com/);
});

test('mobile planning excludes operations without render support or frame evidence', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  for (const name of ['planner-core.cjs', 'mobile-planner.cjs']) {
    const source = fs.readFileSync(path.join(__dirname, '..', 'server', name), 'utf8');
    assert.doesNotMatch(source.match(/const allowed = new Set\(\[[^\n]+/)[0], /set_color_grade|add_transition|add_motion_preset/);
    assert.match(source, /Color requires frame evidence/);
    assert.match(source, /Transitions and motion presets are unavailable/);
  }
});

test('mobile planner accepts only bounded evidence packets and grounded output references', () => {
  const request = {
    prompt: 'make a first cut',
    media: { durationMs: 1000, width: 1080, height: 1920, rotation: 0, hasAudio: true },
    preferences: {},
    operations: [],
    projectRevision: 4,
    evidence: {
      schemaVersion: 1,
      evidenceFingerprint: 'graph-version',
      items: [{
        id: 'sentence-1', type: 'TRANSCRIPT', sourceId: 'source-1',
        range: { startMs: 0, endMs: 500 }, confidence: 0.9, summary: 'Hello',
        derivedFromEvidenceIds: [],
      }],
      exhaustedTypes: [],
      omittedCounts: {},
    },
  };
  assert.equal(plannerCore.validateRequest(request), true);
  assert.equal(plannerCore.validateRequest({ ...request, evidence: { ...request.evidence, items: [
    { ...request.evidence.items[0], artifactPath: '/private/frame.jpg' },
  ] } }), false);
  assert.throws(() => plannerCore.validateProposal({
    type: 'proposal', expectedRevision: 4, summary: 'cut', operations: [],
    evidenceIds: ['invented'],
  }, 4, new Set(['sentence-1'])), /invented evidence/);
  const grounding = {
    rationale: 'The spoken opening establishes the subject.',
    evidenceIds: ['sentence-1'],
    confidence: 0.9,
    assumptions: [],
    expectedStoryEffect: 'Open with context.',
  };
  const proposal = {
    type: 'proposal', expectedRevision: 4, summary: 'cut', operations: [],
    evidenceIds: ['sentence-1'],
    plan: {
      story: [{ beatId: 'beat-opening', ...grounding }],
      decisions: [{ id: 'decision-story', stage: 'STORY', kind: 'REQUIRED_CLEANUP', ...grounding }],
      operationPlans: [],
    },
  };
  assert.equal(plannerCore.validateProposal(proposal, 4, new Set(['sentence-1'])).summary, 'cut');
  assert.throws(() => plannerCore.validateProposal({
    ...proposal,
    plan: { ...proposal.plan, story: proposal.plan.story.map(({ evidenceIds, ...choice }) => choice) },
  }, 4, new Set(['sentence-1'])), /story and take plan/);
});

test('staged plans keep cleanup separate and explain every typed operation', () => {
  const evidence = new Set(['beat-demo', 'audio-noise']);
  const grounded = (evidenceIds, expectedStoryEffect) => ({
    rationale: 'Grounded in the retrieved footage evidence.', evidenceIds, confidence: 0.88,
    assumptions: [], expectedStoryEffect,
  });
  const proposal = {
    type: 'proposal', expectedRevision: 8, summary: 'Demo-led cut',
    evidenceIds: [...evidence],
    operations: [{ id: 'cleanup-1', type: 'set_audio_cleanup', enabled: true, strength: 0.5 }],
    plan: {
      story: [{ beatId: 'beat-demo', ...grounded(['beat-demo'], 'Lead with the demonstration.') }],
      decisions: [
        { id: 'story-1', stage: 'STORY', kind: 'REQUIRED_CLEANUP', ...grounded(['beat-demo'], 'Lead with the demonstration.') },
        { id: 'correction-1', stage: 'CORRECTION', kind: 'REQUIRED_CLEANUP', ...grounded(['audio-noise'], 'Make the explanation intelligible.') },
      ],
      operationPlans: [{
        operationId: 'cleanup-1', decisionId: 'correction-1',
        ...grounded(['audio-noise'], 'Make the explanation intelligible.'),
      }],
    },
  };
  assert.equal(plannerCore.validateProposal(proposal, 8, evidence).summary, 'Demo-led cut');
  assert.throws(() => plannerCore.validateProposal({
    ...proposal,
    plan: { ...proposal.plan, decisions: proposal.plan.decisions.map((decision) =>
      decision.id === 'correction-1' ? { ...decision, kind: 'OPTIONAL_STYLE' } : decision) },
  }, 8, evidence), /cleanup must be separate/);
});

test('identical preferences can yield materially footage-specific plans without decorative variation', () => {
  const preferences = { pace: 'NATURAL', music: 'NONE', captions: true };
  const talkingHeadPlan = {
    preferences,
    story: ['hook-claim', 'explanation', 'cta'],
    selectedTakes: ['take-clear-delivery'],
    operations: ['remove_fumbles', 'add_captions'],
  };
  const productDemoPlan = {
    preferences,
    story: ['problem', 'product-demonstration', 'proof', 'cta'],
    selectedTakes: ['take-product-state-correct'],
    operations: ['set_audio_cleanup', 'add_captions'],
  };
  assert.notDeepEqual(talkingHeadPlan.story, productDemoPlan.story);
  assert.notDeepEqual(talkingHeadPlan.selectedTakes, productDemoPlan.selectedTakes);
  assert.notDeepEqual(talkingHeadPlan.operations, productDemoPlan.operations);
  for (const plan of [talkingHeadPlan, productDemoPlan]) {
    assert.equal(plan.operations.includes('add_transition'), false);
    assert.equal(plan.operations.includes('add_motion_preset'), false);
    assert.equal(plan.operations.includes('set_background_music'), false);
    assert.equal(plan.operations.includes('add_sound_effect'), false);
  }
});
