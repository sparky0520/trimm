'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { AgentRuns } = require('../server/agent-runs.cjs');
const { VolatileAgentRunStore } = require('../server/agent-run-store.cjs');

const principal = { id: 'user-1' };
const inventory = { projectId: 'project-1', projectRevision: 0 };

test('publishable run exposes safe activity and a previewable proposal', async () => {
  const runs = new AgentRuns({
    orchestrator: { orchestrate: async () => ({ type: 'edit_program', expectedRevision: 0, summary: 'Tighter first cut', operations: [] }) },
    now: () => new Date('2026-07-29T00:00:00.000Z'),
    id: () => 'run-1',
  });
  const run = await runs.start(principal, inventory);
  assert.equal(run.contractVersion, 1);
  assert.equal(run.status, 'proposal_ready');
  assert.deepEqual(run.events.map(({ type }) => type), ['started', 'index_received', 'analyzing', 'proposal_ready']);
  assert.equal((await runs.read(principal, 'run-1')).decision.type, 'edit_program');
  await assert.rejects(runs.read({ id: 'other' }, 'run-1'), /not found/);
});

test('an evidence request continues the same run without a pass limit', async () => {
  let calls = 0;
  const runs = new AgentRuns({
    orchestrator: { orchestrate: async (_principal, value) => {
      calls += 1;
      return calls === 1
        ? { type: 'evidence_request', jobs: [{ id: 'job-1' }] }
        : { type: 'edit_program', expectedRevision: 0, summary: 'Grounded cut', operations: [], evidenceJobIds: value.evidenceJobIds };
    } },
    now: () => new Date('2026-07-29T00:00:00.000Z'),
    id: () => 'run-2',
  });
  const waiting = await runs.start(principal, { ...inventory, evidenceJobIds: [] });
  assert.equal(waiting.status, 'waiting_for_evidence');
  const ready = await runs.continue(principal, 'run-2', { evidenceJobIds: ['job-1'] });
  assert.equal(ready.runId, waiting.runId);
  assert.equal(ready.status, 'proposal_ready');
  assert.deepEqual(ready.events.slice(-3).map(({ type }) => type), ['evidence_received', 'analyzing', 'proposal_ready']);
});

test('a second service instance resumes the same persisted run', async () => {
  const store = new VolatileAgentRunStore();
  let calls = 0;
  const orchestrator = { orchestrate: async () => {
    calls += 1;
    return calls === 1
      ? { type: 'evidence_request', jobs: [{ id: 'job-1' }] }
      : { type: 'edit_program', expectedRevision: 0, summary: 'Persisted cut', operations: [] };
  } };
  const firstInstance = new AgentRuns({
    orchestrator, store,
    now: () => new Date('2026-07-29T00:00:00.000Z'),
    id: () => 'run-persisted',
  });
  const waiting = await firstInstance.start(principal, inventory);
  assert.equal(waiting.status, 'waiting_for_evidence');

  const restartedInstance = new AgentRuns({ orchestrator, store });
  assert.equal((await restartedInstance.read(principal, waiting.runId)).status, 'waiting_for_evidence');
  const ready = await restartedInstance.continue(principal, waiting.runId, { evidenceJobIds: ['job-1'] });
  assert.equal(ready.status, 'proposal_ready');
  assert.equal(ready.runId, waiting.runId);
});

test('optimistic versions reject concurrent stale run updates', async () => {
  const store = new VolatileAgentRunStore();
  const created = await store.create({
    id: 'run-1', ownerId: 'user-1', projectId: 'project-1', projectRevision: 0,
    status: 'partial', events: [], inventory, decision: { type: 'fallback', reason: 'Starting.' },
  });
  const stale = await store.read('user-1', 'run-1');
  await store.save({ ...created, status: 'waiting_for_user' }, created.stateVersion);
  await assert.rejects(
    store.save({ ...stale, status: 'cancelled' }, stale.stateVersion),
    (error) => error.code === 'agent_run_conflict' && error.status === 409,
  );
});

test('failed orchestration is persisted as safe terminal activity', async () => {
  const store = new VolatileAgentRunStore();
  const runs = new AgentRuns({
    store,
    orchestrator: { orchestrate: async () => { throw new Error('private provider detail'); } },
    id: () => 'run-failed',
  });
  await assert.rejects(runs.start(principal, inventory));
  const persisted = await runs.read(principal, 'run-failed');
  assert.equal(persisted.status, 'failed');
  assert.equal(persisted.decision.reason, 'The analysis could not be completed.');
  assert.equal(persisted.events.at(-1).message, 'The analysis stopped safely and can be retried.');
});

test('clarification and bounded fallback become safe persisted run states', async () => {
  for (const [decision, status, eventType] of [
    [{ type: 'clarification', question: 'Which pacing do you prefer?', options: ['tight', 'relaxed'] }, 'waiting_for_user', 'clarification'],
    [{ type: 'fallback', reason: 'The evidence budget was reached.' }, 'partial', 'partial'],
  ]) {
    const store = new VolatileAgentRunStore();
    const runs = new AgentRuns({
      store,
      orchestrator: { orchestrate: async () => decision },
      id: () => `run-${eventType}`,
    });
    const result = await runs.start(principal, inventory);
    assert.equal(result.status, status);
    assert.equal(result.decision.type, decision.type);
    assert.equal(result.events.at(-1).type, eventType);
    assert.equal((await runs.read(principal, result.runId)).status, status);
  }
});
