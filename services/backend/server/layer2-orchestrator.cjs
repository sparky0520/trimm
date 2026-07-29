'use strict';

const { validateMediaIndex } = require('./media-index-contract.cjs');
const ALLOWED_OPERATIONS = new Set([
  'remove_fumbles', 'set_audio_cleanup', 'set_audio_levels',
  'add_sound_effect', 'set_background_music', 'add_captions', 'semantic_visual',
]);
const ALLOWED_DERIVATIVES = Object.freeze({
  audio: Object.freeze({ formats: new Set(['audio/mpeg', 'audio/mp4', 'audio/ogg']), purposes: new Set(['transcription', 'speech-analysis', 'audio-analysis']) }),
  frame: Object.freeze({ formats: new Set(['image/jpeg', 'image/webp']), purposes: new Set(['visual-analysis', 'shot-analysis', 'planner-evidence']) }),
  crop: Object.freeze({ formats: new Set(['image/jpeg', 'image/webp']), purposes: new Set(['ocr', 'ui-analysis', 'planner-evidence']) }),
  text: Object.freeze({ formats: new Set(['text/plain', 'application/json']), purposes: new Set(['planning', 'transcript-analysis']) }),
  evidence: Object.freeze({ formats: new Set(['application/json']), purposes: new Set(['planning', 'candidate-evaluation']) }),
});
const FORBIDDEN_KEYS = new Set([
  'prompt', 'systemPrompt', 'tools', 'toolSchema', 'provider', 'providerKey',
  'apiKey', 'command', 'shell', 'executable', 'sourceUri', 'renderedOutput',
]);
const DEFAULT_BUDGETS = Object.freeze({
  maximumBytes: 8 * 1024 * 1024,
  maximumDurationMs: 10 * 60 * 1000,
  maximumCostMicros: 50_000,
  maximumRequests: 8,
  retentionMs: 6 * 60 * 60 * 1000,
});
const SYSTEM_POLICY = `You are ctrlB's private Layer 2 editorial orchestrator for desktop and mobile.
Return one JSON object with type evidence_request, edit_program, clarification, or fallback.
Begin with the request-shaped media index in the compact inventory. Treat its ranges as locally measured candidates and coverage, not as editorial meaning.
Ask only for the minimum derivative evidence needed. You may request additional bounded ranges when the current index is insufficient.
Never request source video, rendered output, executable instructions, provider credentials, or tools.
Every edit operation must cite supplied evidence IDs and include confidence from 0 to 1.
Do not invent evidence. When evidence is missing or uncertain, request bounded evidence, clarify, or fall back.
Supported operations: remove_fumbles, set_audio_cleanup, set_audio_levels, add_sound_effect, set_background_music, add_captions, semantic_visual.
Semantic visuals require a cited face, screen, product, OCR, or salience subject with normalized bounds. Unsafe reframing must return clarification or fallback.`;

function orchestrationError(message, status = 400, code = 'invalid_orchestration') {
  return Object.assign(new Error(message), { status, code });
}

function boundedString(value, name, pattern = /^[a-zA-Z0-9._:-]{1,200}$/) {
  if (typeof value !== 'string' || !pattern.test(value)) throw orchestrationError(`Invalid ${name}.`);
  return value;
}

function boundedInteger(value, name, maximum, allowZero = false) {
  if (!Number.isSafeInteger(value) || value < (allowZero ? 0 : 1) || value > maximum) {
    throw orchestrationError(`Invalid ${name}.`);
  }
  return value;
}

function rejectPrivateOrExecutable(value) {
  if (!value || typeof value !== 'object') return;
  for (const [key, child] of Object.entries(value)) {
    if (FORBIDDEN_KEYS.has(key)) throw orchestrationError('Private or executable planner fields are forbidden.', 400, 'cloud_boundary_violation');
    rejectPrivateOrExecutable(child);
  }
}

function validateInventory(value) {
  if (!value || typeof value !== 'object') throw orchestrationError('Invalid project inventory.');
  for (const name of ['agentRuns', 'activityEvents', 'evidenceRequests', 'editPrograms']) {
    if (!Array.isArray(value.supportedContractVersions?.[name]) ||
        !value.supportedContractVersions[name].includes(1)) {
      throw orchestrationError('No mutually supported agent contract version.', 406, 'contract_version_not_supported');
    }
  }
  rejectPrivateOrExecutable(value);
  const inventory = {
    projectId: boundedString(value.projectId, 'project ID'),
    deviceSessionId: boundedString(value.deviceSessionId, 'device session ID'),
    projectRevision: boundedInteger(value.projectRevision, 'project revision', Number.MAX_SAFE_INTEGER, true),
    intent: typeof value.intent === 'string' && value.intent.length <= 2000 ? value.intent : '',
    sources: Array.isArray(value.sources) ? value.sources.slice(0, 100).map((source) => ({
      id: boundedString(source.id, 'source ID'),
      fingerprint: boundedString(source.fingerprint, 'source fingerprint', /^[a-f0-9]{32,128}$/),
      durationMs: boundedInteger(source.durationMs, 'source duration', 24 * 60 * 60 * 1000),
      width: boundedInteger(source.width, 'source width', 16_384),
      height: boundedInteger(source.height, 'source height', 16_384),
      hasAudio: Boolean(source.hasAudio),
    })) : [],
    evidenceJobIds: Array.isArray(value.evidenceJobIds)
      ? value.evidenceJobIds.slice(0, 100).map((id) => boundedString(id, 'evidence job ID', /^[0-9a-f-]{36}$/i))
      : [],
    existingOperations: Array.isArray(value.existingOperations) ? value.existingOperations.slice(0, 200) : [],
    supportedContractVersions: {
      agentRuns: [1], activityEvents: [1], evidenceRequests: [1], editPrograms: [1],
    },
  };
  if (value.mediaConsent?.mode !== 'selective_analysis' ||
      typeof value.mediaConsent.grantedAt !== 'string' || !Number.isFinite(Date.parse(value.mediaConsent.grantedAt)) ||
      typeof value.mediaConsent.disclosureVersion !== 'string') {
    throw orchestrationError('Selective media consent is required.', 400, 'consent_required');
  }
  inventory.mediaConsent = {
    mode: 'selective_analysis',
    grantedAt: new Date(value.mediaConsent.grantedAt).toISOString(),
    disclosureVersion: boundedString(value.mediaConsent.disclosureVersion, 'disclosure version'),
  };
  if (!inventory.sources.length) throw orchestrationError('Project inventory requires at least one source.');
  if (new Set(inventory.sources.map((source) => source.id)).size !== inventory.sources.length) {
    throw orchestrationError('Source IDs must be unique.');
  }
  inventory.mediaIndex = validateMediaIndex(value.mediaIndex, inventory.sources);
  const requested = value.budgets || {};
  inventory.budgets = {
    maximumBytes: boundedInteger(requested.maximumBytes ?? DEFAULT_BUDGETS.maximumBytes, 'byte budget', 100 * 1024 * 1024),
    maximumDurationMs: boundedInteger(requested.maximumDurationMs ?? DEFAULT_BUDGETS.maximumDurationMs, 'duration budget', 3 * 60 * 60 * 1000),
    maximumCostMicros: boundedInteger(requested.maximumCostMicros ?? DEFAULT_BUDGETS.maximumCostMicros, 'cost budget', 100_000_000, true),
    maximumRequests: boundedInteger(requested.maximumRequests ?? DEFAULT_BUDGETS.maximumRequests, 'request budget', 20),
    retentionMs: boundedInteger(requested.retentionMs ?? DEFAULT_BUDGETS.retentionMs, 'retention budget', 24 * 60 * 60 * 1000),
  };
  return inventory;
}

function evidenceFor(inventory, principal, derivativeJobs) {
  return inventory.evidenceJobIds.map((jobId) => {
    const job = derivativeJobs.read(jobId, principal, {
      projectId: inventory.projectId,
      deviceSessionId: inventory.deviceSessionId,
    });
    if (job.status !== 'completed' || !job.result) {
      throw orchestrationError('Evidence is not ready.', 409, 'evidence_not_ready');
    }
    return {
      id: job.id,
      type: job.type,
      purpose: job.purpose,
      contentKey: job.contentKey,
      value: job.result.value,
      provenance: job.result.provenance,
    };
  });
}

function validateEvidenceRequest(decision, inventory) {
  if (!Array.isArray(decision.requests) || !decision.requests.length || decision.requests.length > inventory.budgets.maximumRequests) {
    throw orchestrationError('Planner evidence request exceeds the request budget.', 422, 'evidence_budget_exceeded');
  }
  const sourceById = new Map(inventory.sources.map((source) => [source.id, source]));
  let bytes = 0; let duration = 0; let cost = 0;
  const requests = decision.requests.map((request, index) => {
    const source = sourceById.get(request.sourceId);
    const policy = ALLOWED_DERIVATIVES[request.type];
    if (!source || !policy || !policy.formats.has(request.acceptedFormat) || !policy.purposes.has(request.purpose)) {
      throw orchestrationError('Planner requested unsupported evidence.', 422, 'unsupported_evidence_request');
    }
    const startMs = boundedInteger(request.startMs ?? 0, 'evidence start', source.durationMs, true);
    const endMs = boundedInteger(request.endMs ?? source.durationMs, 'evidence end', source.durationMs);
    if (endMs <= startMs) throw orchestrationError('Planner requested an invalid evidence range.', 422, 'invalid_evidence_range');
    const maximumBytes = boundedInteger(request.maximumBytes, 'evidence bytes', inventory.budgets.maximumBytes);
    const maximumDurationMs = request.type === 'audio'
      ? boundedInteger(request.maximumDurationMs ?? endMs - startMs, 'evidence duration', endMs - startMs)
      : null;
    const estimatedCostMicros = boundedInteger(request.estimatedCostMicros ?? 0, 'evidence cost', inventory.budgets.maximumCostMicros, true);
    bytes += maximumBytes; duration += maximumDurationMs || 0; cost += estimatedCostMicros;
    return {
      source, sourceId: source.id, type: request.type, purpose: request.purpose,
      acceptedFormat: request.acceptedFormat, startMs, endMs, maximumBytes,
      maximumDurationMs, maximumCount: request.maximumCount ?? 1,
      estimatedCostMicros, requestIndex: index,
    };
  });
  if (bytes > inventory.budgets.maximumBytes || duration > inventory.budgets.maximumDurationMs || cost > inventory.budgets.maximumCostMicros) {
    throw orchestrationError('Planner evidence request exceeds project budgets.', 422, 'evidence_budget_exceeded');
  }
  return requests;
}

function validateEditProgram(decision, inventory, evidence) {
  if (decision.expectedRevision !== inventory.projectRevision || !Array.isArray(decision.operations)) {
    throw orchestrationError('Invalid edit program envelope.', 422, 'invalid_edit_program');
  }
  const evidenceIds = new Set(evidence.map((item) => item.id));
  const operationIds = new Set();
  for (const operation of decision.operations) {
    if (!operation || !ALLOWED_OPERATIONS.has(operation.type) || typeof operation.id !== 'string' ||
        typeof operation.enabled !== 'boolean' || operationIds.has(operation.id)) {
      throw orchestrationError('Planner returned an unsupported edit operation.', 422, 'invalid_edit_program');
    }
    operationIds.add(operation.id);
    if (typeof operation.confidence !== 'number' || operation.confidence < 0 || operation.confidence > 1 ||
        !Array.isArray(operation.evidenceIds) || !operation.evidenceIds.length ||
        operation.evidenceIds.some((id) => !evidenceIds.has(id))) {
      throw orchestrationError('Edit operation has missing, stale, or invented evidence.', 422, 'invalid_evidence_reference');
    }
    if (operation.type === 'semantic_visual') {
      const subject = operation.subject;
      const bounds = subject?.bounds;
      const values = bounds && [bounds.left, bounds.top, bounds.right, bounds.bottom];
      const range = operation.range;
      if (!['INTRODUCE', 'POINT_OUT', 'TRANSITION', 'REVEAL', 'EMPHASIZE', 'RECAP'].includes(operation.intent) ||
          !['CALLOUT', 'SPOTLIGHT', 'REFRAME'].includes(operation.treatment) ||
          !range || !Number.isSafeInteger(range.startMs) || !Number.isSafeInteger(range.endMs) ||
          range.startMs < 0 || range.endMs - range.startMs < 300 || range.endMs - range.startMs > 8000 ||
          !subject || !evidenceIds.has(subject.evidenceId) ||
          !['FACE', 'SCREEN_UI', 'PRODUCT', 'OCR_TEXT', 'SALIENCE'].includes(subject.kind) ||
          typeof subject.confidence !== 'number' || subject.confidence < .55 || subject.confidence > 1 ||
          !values || values.some((value) => typeof value !== 'number' || value < 0 || value > 1) ||
          bounds.right <= bounds.left || bounds.bottom <= bounds.top ||
          operation.treatment === 'REFRAME' && (!operation.framing ||
            operation.framing.preservesSubject !== true || operation.framing.preservesText !== true)) {
        throw orchestrationError('Semantic visual is unsafe or ungrounded.', 422, 'invalid_edit_program');
      }
    }
  }
  return {
    type: 'edit_program',
    expectedRevision: decision.expectedRevision,
    summary: typeof decision.summary === 'string' ? decision.summary.slice(0, 2000) : '',
    operations: decision.operations,
  };
}

function validateDecision(decision, inventory, evidence) {
  if (!decision || typeof decision !== 'object') throw orchestrationError('Planner returned no decision.', 502, 'invalid_planner_response');
  rejectPrivateOrExecutable(decision);
  if (decision.type === 'evidence_request') return { type: decision.type, requests: validateEvidenceRequest(decision, inventory) };
  if (decision.type === 'edit_program') return validateEditProgram(decision, inventory, evidence);
  if (decision.type === 'clarification') {
    if (typeof decision.question !== 'string' || !decision.question.trim() || decision.question.length > 500) {
      throw orchestrationError('Invalid clarification.', 502, 'invalid_planner_response');
    }
    return { type: 'clarification', question: decision.question, options: Array.isArray(decision.options) ? decision.options.slice(0, 5) : [] };
  }
  if (decision.type === 'fallback') {
    return { type: 'fallback', reason: typeof decision.reason === 'string' ? decision.reason.slice(0, 1000) : 'Insufficient evidence.' };
  }
  throw orchestrationError('Planner returned an unsupported decision.', 502, 'invalid_planner_response');
}

class Layer2Orchestrator {
  constructor({ derivativeJobs, decide }) {
    if (!derivativeJobs || typeof decide !== 'function') throw new TypeError('Layer2Orchestrator dependencies are required.');
    this.derivativeJobs = derivativeJobs;
    this.decide = decide;
  }

  async orchestrate(principal, payload) {
    const inventory = validateInventory(payload);
    const evidence = evidenceFor(inventory, principal, this.derivativeJobs);
    const raw = await this.decide({
      systemPolicy: SYSTEM_POLICY,
      inventory,
      evidence,
    });
    const decision = validateDecision(raw, inventory, evidence);
    if (decision.type !== 'evidence_request') return decision;
    const jobs = [];
    try {
      for (const request of decision.requests) {
        const authorized = await this.derivativeJobs.authorize(principal, {
          projectId: inventory.projectId,
          deviceSessionId: inventory.deviceSessionId,
          purpose: request.purpose,
          type: request.type,
          acceptedFormat: request.acceptedFormat,
          maximumBytes: request.maximumBytes,
          maximumDurationMs: request.maximumDurationMs,
          maximumCount: request.maximumCount,
          estimatedCostMicros: request.estimatedCostMicros,
          mediaMode: 'selective_derivative',
          consent: inventory.mediaConsent,
          selectionReason: 'agent_requested',
          retentionMs: inventory.budgets.retentionMs,
          providerDisclosures: [{
            provider: 'google-gemini',
            purpose: 'multimodal-planning',
            dataClasses: [request.type],
          }],
          idempotencyKey: `planner:${inventory.projectRevision}:${request.requestIndex}:${request.source.fingerprint.slice(0, 16)}`,
          sourceFingerprint: request.source.fingerprint,
          timelineReferences: [{ sourceId: request.sourceId, startMs: request.startMs, endMs: request.endMs }],
        });
        jobs.push({
          id: authorized.job.id,
          sourceId: request.sourceId,
          type: request.type,
          purpose: request.purpose,
          acceptedFormat: request.acceptedFormat,
          range: { startMs: request.startMs, endMs: request.endMs },
          maximumBytes: request.maximumBytes,
          maximumDurationMs: request.maximumDurationMs,
          expiresAt: new Date(authorized.job.expiresAt).toISOString(),
          reused: authorized.reused,
        });
      }
    } catch (error) {
      for (const job of jobs.filter((item) => !item.reused)) {
        try {
          this.derivativeJobs.cancel(job.id, principal, {
            projectId: inventory.projectId,
            deviceSessionId: inventory.deviceSessionId,
          });
        } catch {}
      }
      throw error;
    }
    return { type: 'evidence_request', jobs };
  }
}

module.exports = {
  Layer2Orchestrator, SYSTEM_POLICY, validateDecision, validateInventory,
};
