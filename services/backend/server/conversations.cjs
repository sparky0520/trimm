'use strict';

class Conversations {
  constructor({ agentRuns }) {
    if (!agentRuns) throw new TypeError('Conversations requires agent runs persistence.');
    this.agentRuns = agentRuns;
  }

  view(run) {
    const status = ({ waiting_for_evidence: 'waiting_for_tool', proposal_ready: 'awaiting_approval', partial: 'completed' })[run.status] || run.status;
    const decision = run.decision || {};
    const outcome = decision.type === 'tool_request' ? {
      type: 'tool_call', name: decision.tool,
      calls: (decision.jobs || []).map((job) => ({ ...job, toolCallId: job.toolCallId || job.id })),
    } : decision.type === 'edit_program' ? {
      ...decision, type: 'proposal', proposalId: `proposal-${run.runId}-${run.projectRevision}`,
    } : decision;
    return {
      contractVersion: 3,
      conversationId: run.runId,
      projectId: run.projectId,
      projectRevision: run.projectRevision,
      status,
      events: run.events,
      outcome,
      usage: run.usage,
    };
  }

  async create(principal, request) { return this.view(await this.agentRuns.start(principal, request)); }
  async read(principal, id) { return this.view(await this.agentRuns.read(principal, id)); }
  async message(principal, id, request) {
    const toolArtifacts = Array.isArray(request?.toolArtifacts) ? request.toolArtifacts : [];
    const update = toolArtifacts.length ? {
      evidenceJobIds: toolArtifacts.map((item) => item?.artifactId).filter(Boolean),
    } : request;
    return this.view(await this.agentRuns.continue(principal, id, update));
  }
  async cancel(principal, id) { return this.view(await this.agentRuns.cancel(principal, id)); }
  async review(principal, id, proposalId, action, request) {
    void proposalId;
    return this.view(await this.agentRuns.review(principal, id, action, request));
  }
}

module.exports = { Conversations };
