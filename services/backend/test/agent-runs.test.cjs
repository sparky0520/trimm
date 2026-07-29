'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { AgentRuns } = require('../server/agent-runs.cjs');

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
  assert.equal(runs.read(principal, 'run-1').decision.type, 'edit_program');
  assert.throws(() => runs.read({ id: 'other' }, 'run-1'), /not found/);
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
