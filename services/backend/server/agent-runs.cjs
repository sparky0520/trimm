'use strict';

const crypto = require('node:crypto');

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
  constructor({ orchestrator, now = () => new Date(), id = () => crypto.randomUUID() }) {
    this.orchestrator = orchestrator;
    this.now = now;
    this.id = id;
    this.runs = new Map();
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
    const run = {
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
    this.runs.set(run.id, run);
    try {
      run.decision = await this.orchestrator.orchestrate(principal, inventory);
      run.status = STATUS_FOR[run.decision.type];
      this.event(run, ...EVENT_FOR[run.decision.type]);
    } catch (error) {
      run.status = 'failed';
      run.decision = { type: 'fallback', reason: 'The analysis could not be completed.' };
      this.event(run, 'failed', 'The analysis stopped safely and can be retried.');
      throw Object.assign(error, { run: this.view(run) });
    }
    return this.view(run);
  }

  async continue(principal, runId, update) {
    const run = this.owned(principal, runId);
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
    try {
      run.decision = await this.orchestrator.orchestrate(principal, run.inventory);
      run.status = STATUS_FOR[run.decision.type];
      this.event(run, ...EVENT_FOR[run.decision.type]);
    } catch (error) {
      run.status = 'failed';
      run.decision = { type: 'fallback', reason: 'The analysis could not be completed.' };
      this.event(run, 'failed', 'The analysis stopped safely and can be retried.');
      throw Object.assign(error, { run: this.view(run) });
    }
    return this.view(run);
  }

  owned(principal, runId) {
    const run = this.runs.get(runId);
    if (!run || run.ownerId !== principal.id) {
      throw Object.assign(new Error('Agent run not found.'), { status: 404, code: 'agent_run_not_found' });
    }
    return run;
  }

  read(principal, runId) {
    return this.view(this.owned(principal, runId));
  }
}

module.exports = { AgentRuns };
