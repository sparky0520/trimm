'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { Conversations } = require('../server/conversations.cjs');

const run = {
  contractVersion: 2, runId: '00000000-0000-4000-8000-000000000001', projectId: 'project-1',
  projectRevision: 2, status: 'waiting_for_evidence', events: [], usage: {},
  decision: { type: 'tool_request', tool: 'inspect_video_range', jobs: [{ id: 'artifact-1' }] },
};

test('conversation facade exposes chat-oriented tool calls and hides internal run terminology', async () => {
  let update;
  const conversations = new Conversations({ agentRuns: {
    start: async () => run, read: async () => run, cancel: async () => run,
    continue: async (_principal, _id, value) => { update = value; return { ...run, status: 'partial', decision: { type: 'fallback', reason: 'done' } }; },
  } });
  const created = await conversations.create({ id: 'user-1' }, {});
  assert.equal(created.contractVersion, 3);
  assert.equal(created.conversationId, run.runId);
  assert.equal(created.status, 'waiting_for_tool');
  assert.equal(created.outcome.type, 'tool_call');
  assert.equal(created.outcome.calls[0].toolCallId, 'artifact-1');
  const completed = await conversations.message({ id: 'user-1' }, run.runId, {
    toolArtifacts: [{ toolCallId: 'artifact-1', artifactId: 'artifact-1' }],
  });
  assert.deepEqual(update, { evidenceJobIds: ['artifact-1'] });
  assert.equal(completed.status, 'completed');
});
