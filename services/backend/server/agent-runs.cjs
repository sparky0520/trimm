'use strict';

const crypto = require('node:crypto');
const { VolatileAgentRunStore } = require('./agent-run-store.cjs');
const { usageSummary } = require('./model-usage.cjs');
const { AgentGraph } = require('./agent-graph.cjs');

const STATUS_FOR = Object.freeze({
  tool_request: 'waiting_for_evidence',
  edit_program: 'proposal_ready',
  clarification: 'waiting_for_user',
  fallback: 'partial',
});

const EVENT_FOR = Object.freeze({
  tool_request: ['evidence_requested', 'I need to inspect a video range before proposing edits.'],
  edit_program: ['proposal_ready', 'The proposed edit is ready to preview.'],
  clarification: ['clarification', 'I need one choice from you before continuing.'],
  fallback: ['partial', 'I could only complete part of the analysis.'],
});

class AgentRuns {
  constructor({ orchestrator, store = new VolatileAgentRunStore(), now = () => new Date(), id = () => crypto.randomUUID() }) {
    this.orchestrator = orchestrator;
    this.store = store;
    this.now = now;
    this.id = id;
    this.graph = new AgentGraph({ orchestrator });
  }

  event(run, type, message) {
    run.events.push({
      sequence: run.events.length + 1,
      type,
      message,
      createdAt: this.now().toISOString(),
    });
  }

  view(run) {
    return {
      contractVersion: 2,
      runId: run.id,
      projectId: run.projectId,
      projectRevision: run.projectRevision,
      status: run.status,
      events: run.events.map((event) => ({ ...event })),
      decision: run.decision,
      usage: usageSummary(run.usage),
    };
  }

  applyDecision(run, outcome) {
    run.attempts ||= [];
    const recovery = outcome?._recovery;
    const agentState = outcome?._agentState;
    run.usage = usageSummary([...(run.usage?.calls || run.usage || []), ...(outcome?._usage || [])]);
    const decision = Object.fromEntries(
      Object.entries(outcome).filter(([key]) => !['_recovery', '_usage', '_agentState'].includes(key))
    );
    if (agentState) {
      run.inventory = {
        ...run.inventory,
        agentDraft: {
          operations: agentState.operations || [],
        },
        observations: [...(run.inventory.observations || []), ...(agentState.observations || [])],
      };
    } else if (decision.type !== 'tool_request') {
      run.inventory = { ...run.inventory, agentDraft: null };
    }

    run.decision = decision;
    run.inventory.messages = [...(run.inventory.messages || []), {
      role: 'assistant', content: JSON.stringify(decision),
    }].slice(-30);
    if (Array.isArray(decision.observations) && decision.observations.length) {
      run.inventory = { ...run.inventory, observations: [...(run.inventory.observations || []), ...decision.observations] };
    }
    run.status = STATUS_FOR[decision.type];
    this.event(run, ...EVENT_FOR[decision.type]);
  }


  async start(principal, inventory) {
    const initialIntent = typeof inventory?.intent === 'string' ? inventory.intent.trim() : '';
    inventory = {
      ...inventory,
      messages: Array.isArray(inventory?.messages) ? inventory.messages : initialIntent
        ? [{ role: 'user', content: initialIntent }]
        : [],
    };
    let run = {
      id: this.id(),
      ownerId: principal.id,
      projectId: inventory.projectId,
      projectRevision: inventory.projectRevision,
      status: 'partial',
      events: [],
      inventory,
      decision: { type: 'fallback', reason: 'The run has not started.' },
      attempts: [],
      recovery: null,
      usage: usageSummary(),
    };
    this.event(run, 'started', 'Starting a request-shaped video edit.');
    this.event(run, 'transcript_received', 'The complete timestamped transcript is ready.');
    this.event(run, 'analyzing', 'Reviewing the transcript and authorized video for a publishable first cut.');
    run = await this.store.create(run);
    try {
      this.applyDecision(run, await this.graph.invoke({
        mode: 'start', principal, inventory, threadId: run.id,
      }));
      run = await this.store.save(run, run.stateVersion);
    } catch (error) {

      run.status = 'failed';
      run.decision = { type: 'fallback', reason: 'The analysis could not be completed.' };
      this.event(run, 'failed', 'The analysis stopped safely and can be retried.');
      try { run = await this.store.save(run, run.stateVersion); } catch {}
      throw Object.assign(error, { run: this.view(run) });
    }
    return this.view(run);
  }

  async continue(principal, runId, update) {
    let run = await this.owned(principal, runId);
    const message = typeof update?.message === 'string' ? update.message.trim() : '';
    const conversational = ['waiting_for_user', 'proposal_ready', 'partial'].includes(run.status) && message;
    if (run.status !== 'waiting_for_evidence' && !conversational) {
      throw Object.assign(new Error('Agent run is not waiting for evidence or a user message.'), { status: 409, code: 'agent_run_not_waiting' });
    }
    if (message.length > 2000) {
      throw Object.assign(new Error('The follow-up message is too long.'), { status: 400, code: 'message_too_long' });
    }
    if (conversational) {
      const nextRevision = update?.projectRevision;
      if (nextRevision !== undefined && (!Number.isSafeInteger(nextRevision) || nextRevision < run.projectRevision)) {
        throw Object.assign(new Error('The conversation project revision is stale.'), { status: 409, code: 'stale_project_revision' });
      }
      run.inventory = {
        ...run.inventory,
        intent: message,
        projectRevision: nextRevision ?? run.projectRevision,
        ...(Array.isArray(update?.existingOperations) ? { existingOperations: update.existingOperations.slice(0, 200) } : {}),
        messages: [...(run.inventory.messages || []), { role: 'user', content: message }].slice(-30),
      };
      run.projectRevision = run.inventory.projectRevision;
      this.event(run, 'analyzing', 'Continuing the same conversation with your latest request.');

    } else {
      const evidenceJobIds = Array.isArray(update?.evidenceJobIds) ? update.evidenceJobIds : [];
      if (!evidenceJobIds.length) {
        throw Object.assign(new Error('Completed evidence is required.'), { status: 400, code: 'evidence_required' });
      }
      const outstandingJobIds = Array.isArray(run.decision?.jobs)
        ? run.decision.jobs.map((job) => job.id)
        : [];
      const submitted = [...new Set(evidenceJobIds)].sort();
      const outstanding = [...new Set(outstandingJobIds)].sort();
      if (!outstanding.length ||
          submitted.length !== outstanding.length ||
          submitted.some((id, index) => id !== outstanding[index])) {
        throw Object.assign(new Error('Evidence does not match the jobs requested by this run.'), {
          status: 409,
          code: 'unexpected_evidence',
        });
      }
      run.inventory = { ...run.inventory, evidenceJobIds: [...new Set([...(run.inventory.evidenceJobIds || []), ...evidenceJobIds])] };
      this.event(run, 'evidence_received', 'The requested evidence is ready and attached to this run.');
      this.event(run, 'analyzing', 'Continuing the same edit with the new evidence.');
    }
    run = await this.store.save(run, run.stateVersion);
    try {
      this.applyDecision(run, await this.graph.invoke({
        mode: 'continue', principal, inventory: run.inventory, threadId: run.id,
      }));
      run = await this.store.save(run, run.stateVersion);
    } catch (error) {

      run.status = 'failed';
      run.decision = { type: 'fallback', reason: 'The analysis could not be completed.' };
      this.event(run, 'failed', 'The analysis stopped safely and can be retried.');
      try { run = await this.store.save(run, run.stateVersion); } catch {}
      throw Object.assign(error, { run: this.view(run) });
    }
    return this.view(run);
  }

  async owned(principal, runId) {
    const run = await this.store.read(principal.id, runId);
    if (!run) {
      throw Object.assign(new Error('Agent run not found.'), { status: 404, code: 'agent_run_not_found' });
    }
    return run;
  }

  async read(principal, runId) {
    return this.view(await this.owned(principal, runId));
  }

  async cancel(principal, runId) {
    let run = await this.owned(principal, runId);
    if (['proposal_ready', 'partial', 'failed', 'cancelled'].includes(run.status)) return this.view(run);
    run.status = 'cancelled';
    run.decision = { type: 'fallback', reason: 'The conversation was cancelled.' };
    this.event(run, 'cancelled', 'The video edit was cancelled. Nothing was applied.');
    run = await this.store.save(run, run.stateVersion);
    return this.view(run);
  }

  async review(principal, runId, action, update = {}) {
    let run = await this.owned(principal, runId);
    if (run.status !== 'proposal_ready' || !['accept', 'reject'].includes(action)) {
      throw Object.assign(new Error('The conversation has no pending proposal for this action.'), { status: 409, code: 'proposal_not_pending' });
    }
    if (action === 'accept') {
      if (!Number.isSafeInteger(update.projectRevision) || update.projectRevision <= run.projectRevision || !Array.isArray(update.existingOperations)) {
        throw Object.assign(new Error('Accepted proposal state is required.'), { status: 400, code: 'accepted_project_state_required' });
      }
      run.projectRevision = update.projectRevision;
      run.inventory = { ...run.inventory, projectRevision: update.projectRevision, existingOperations: update.existingOperations.slice(0, 200) };
    }
    const content = action === 'accept' ? 'I accepted this proposal.' : 'I rejected this proposal.';
    run.inventory.messages = [...(run.inventory.messages || []), { role: 'user', content }].slice(-30);
    run.status = 'partial';
    run.decision = { type: 'fallback', reason: action === 'accept' ? 'Proposal accepted.' : 'Proposal rejected.' };
    this.event(run, 'partial', action === 'accept' ? 'The proposal was accepted.' : 'The proposal was rejected without changing the project.');
    run = await this.store.save(run, run.stateVersion);
    return this.view(run);
  }
}

module.exports = { AgentRuns };
