'use strict';

const crypto = require('node:crypto');
const { VolatileAgentRunStore } = require('./agent-run-store.cjs');

const STATUS_FOR = Object.freeze({
  evidence_request: 'waiting_for_evidence',
  edit_program: 'proposal_ready',
  clarification: 'waiting_for_user',
  fallback: 'partial',
});

const EVENT_FOR = Object.freeze({
  evidence_request: ['evidence_requested', 'I need a few precise ranges before proposing edits.'],
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
      contractVersion: 1,
      runId: run.id,
      projectId: run.projectId,
      projectRevision: run.projectRevision,
      status: run.status,
      events: run.events.map((event) => ({ ...event })),
      decision: run.decision,
    };
  }

  async start(principal, inventory) {
    let run = {
      id: this.id(),
      ownerId: principal.id,
      projectId: inventory.projectId,
      projectRevision: inventory.projectRevision,
      status: 'partial',
      events: [],
      inventory,
      decision: { type: 'fallback', reason: 'The run has not started.' },
    };
    this.event(run, 'started', 'Starting a request-shaped video edit.');
    this.event(run, 'index_received', 'The local media index is ready; original source media stays local.');
    this.event(run, 'analyzing', 'Reviewing the selected ranges for a publishable first cut.');
    run = await this.store.create(run);
    try {
      run.decision = await this.orchestrator.orchestrate(principal, inventory);
      run.status = STATUS_FOR[run.decision.type];
      this.event(run, ...EVENT_FOR[run.decision.type]);
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
    if (run.status !== 'waiting_for_evidence') {
      throw Object.assign(new Error('Agent run is not waiting for evidence.'), { status: 409, code: 'agent_run_not_waiting' });
    }
    const evidenceJobIds = Array.isArray(update?.evidenceJobIds) ? update.evidenceJobIds : [];
    if (!evidenceJobIds.length) {
      throw Object.assign(new Error('Completed evidence is required.'), { status: 400, code: 'evidence_required' });
    }
    run.inventory = { ...run.inventory, evidenceJobIds: [...new Set([...(run.inventory.evidenceJobIds || []), ...evidenceJobIds])] };
    this.event(run, 'evidence_received', 'The requested evidence is ready and attached to this run.');
    this.event(run, 'analyzing', 'Continuing the same edit with the new evidence.');
    run = await this.store.save(run, run.stateVersion);
    try {
      run.decision = await this.orchestrator.orchestrate(principal, run.inventory);
      run.status = STATUS_FOR[run.decision.type];
      this.event(run, ...EVENT_FOR[run.decision.type]);
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
}

module.exports = { AgentRuns };
