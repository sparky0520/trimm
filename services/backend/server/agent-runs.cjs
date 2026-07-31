'use strict';

const crypto = require('node:crypto');
const { VolatileAgentRunStore } = require('./agent-run-store.cjs');
const { ProposalRecoveryError } = require('./proposal-recovery.cjs');
const { usageSummary } = require('./model-usage.cjs');

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
    run.usage = usageSummary([...(run.usage?.calls || run.usage || []), ...(outcome?._usage || [])]);
    const decision = Object.fromEntries(
      Object.entries(outcome).filter(([key]) => key !== '_recovery' && key !== '_usage')
    );
    if (recovery) {
      this.event(run, 'proposal_repairing', 'The proposal needed a bounded structural repair.');
      run.attempts.push(...recovery.attempts);
      run.recovery = { status: recovery.status, canRetry: false, canClarify: false };
      this.event(run, 'proposal_repaired', 'The repaired proposal passed validation without changing the edit intent.');
    } else if (run.recovery?.status === 'repair_failed') {
      run.recovery = { ...run.recovery, status: 'resolved', canRetry: false, canClarify: false };
    }
    run.decision = decision;
    if (Array.isArray(decision.observations) && decision.observations.length) {
      run.inventory = { ...run.inventory, observations: [...(run.inventory.observations || []), ...decision.observations] };
    }
    run.status = STATUS_FOR[decision.type];
    this.event(run, ...EVENT_FOR[decision.type]);
  }

  recoverFailure(run, error) {
    if (!(error instanceof ProposalRecoveryError)) return false;
    run.attempts ||= [];
    run.usage = usageSummary([
      ...(run.usage?.calls || run.usage || []),
      ...error.recovery.attempts.map((attempt) => attempt.usage).filter(Boolean),
    ]);
    this.event(run, 'proposal_repairing', 'The proposal was malformed, so a bounded structural repair was attempted.');
    run.attempts.push(...error.recovery.attempts);
    run.recovery = {
      status: 'repair_failed',
      canRetry: true,
      canClarify: true,
      attemptCount: error.recovery.attempts.length,
    };
    run.status = 'waiting_for_user';
    run.decision = {
      type: 'clarification',
      question: 'The proposal could not be repaired safely. Would you like to retry or clarify the edit?',
      options: ['retry', 'clarify'],
    };
    this.event(run, 'proposal_repair_failed', 'Nothing was applied. Your project and accepted edits are unchanged; you can retry or clarify.');
    return true;
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
      attempts: [],
      recovery: null,
      usage: usageSummary(),
    };
    this.event(run, 'started', 'Starting a request-shaped video edit.');
    this.event(run, 'transcript_received', 'The complete timestamped transcript is ready.');
    this.event(run, 'analyzing', 'Reviewing the transcript and authorized video for a publishable first cut.');
    run = await this.store.create(run);
    try {
      const bootstrap = await this.orchestrator.bootstrap(principal, inventory);
      this.applyDecision(run, bootstrap || await this.orchestrator.orchestrate(principal, inventory));
      run = await this.store.save(run, run.stateVersion);
    } catch (error) {
      if (this.recoverFailure(run, error)) {
        run = await this.store.save(run, run.stateVersion);
        return this.view(run);
      }
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
    const recovering = run.status === 'waiting_for_user' && run.recovery?.status === 'repair_failed';
    if (!recovering && run.status !== 'waiting_for_evidence') {
      throw Object.assign(new Error('Agent run is not waiting for evidence.'), { status: 409, code: 'agent_run_not_waiting' });
    }
    if (recovering) {
      if (!['retry', 'clarify'].includes(update?.action)) {
        throw Object.assign(new Error('Retry or clarification is required.'), { status: 400, code: 'recovery_action_required' });
      }
      if (update.action === 'clarify') {
        if (typeof update.clarification !== 'string' || !update.clarification.trim() || update.clarification.length > 500) {
          throw Object.assign(new Error('A bounded clarification is required.'), { status: 400, code: 'clarification_required' });
        }
        run.inventory = {
          ...run.inventory,
          clarifications: [...(run.inventory.clarifications || []), update.clarification.trim()].slice(-10),
        };
      }
      this.event(run, 'analyzing', update.action === 'retry'
        ? 'Retrying the same request without applying the malformed proposal.'
        : 'Retrying the same request with your clarification.');
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
      this.applyDecision(run, await this.orchestrator.orchestrate(principal, run.inventory));
      run = await this.store.save(run, run.stateVersion);
    } catch (error) {
      if (this.recoverFailure(run, error)) {
        run = await this.store.save(run, run.stateVersion);
        return this.view(run);
      }
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
}

module.exports = { AgentRuns };
