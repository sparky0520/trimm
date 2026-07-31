'use strict';

const { recoverProposal } = require('./proposal-recovery.cjs');
const { validateContextualSpeechDecision } = require('./contextual-speech.cjs');
const ALLOWED_OPERATIONS = new Set([
  'remove_fumbles', 'set_audio_cleanup', 'set_audio_levels',
  'add_sound_effect', 'set_background_music', 'add_captions', 'semantic_visual',
]);
const MAX_VIDEO_RANGE_MS = 30_000;
const FORBIDDEN_KEYS = new Set([
  'prompt', 'systemPrompt', 'tools', 'toolSchema', 'provider', 'providerKey',
  'apiKey', 'command', 'shell', 'executable', 'sourceUri', 'renderedOutput',
]);
const DEFAULT_BUDGETS = Object.freeze({
  maximumBytes: 8 * 1024 * 1024,
  maximumDurationMs: 10 * 60 * 1000,
  retentionMs: 6 * 60 * 60 * 1000,
});
const SYSTEM_POLICY = `You are ctrlB's private Layer 2 editorial orchestrator for desktop and mobile.
Return one JSON object with type edit_program, clarification, or fallback, or call inspect_video_range.
Begin with the request-shaped media index in the compact inventory. Treat its ranges as locally measured candidates and coverage, not as editorial meaning.
For speech edits, preserve meaning and truthful intent first, select the strongest complete delivery second, and use recording quality or continuity only as tie-breakers unless a defect makes a take unusable.
Possible fillers, pauses, repeated phrases, transcript confidence, and technical scores only locate candidates; none can authorize a cut.
Before remove_fumbles, compare the local speaking-rate distribution for that speaker and scene, surrounding words/video, word or phoneme timing, waveform and scene-aware noise, alternate takes when present, user intent, and a simulated post-cut transition.
Low transcript confidence requires re-listening or an alternate transcription view; it never means automatic keep or remove.
Every remove_fumbles operation must include speechDecision with meaningPreserved:true, completeDeliverySelected:true, a contextual rationale, supplied contextEvidenceIds, locatorSignals, contextSignals, transcriptUncertain, uncertaintyAction, and technicalTieBreakerOnly.
For sources longer than 30 seconds, use inspect_video_range for the audiovisual ranges needed to verify the transcript and edit. Each range is at most 30 seconds. Use LOW normally. Use HIGH only after an overlapping LOW range left a specific uncertainty.
Never request rendered output, executable instructions, or provider credentials.
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
  for (const name of ['agentRuns', 'activityEvents', 'videoToolRequests', 'editPrograms']) {
    if (!Array.isArray(value.supportedContractVersions?.[name]) ||
        !value.supportedContractVersions[name].includes(2)) {
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
    transcript: Array.isArray(value.transcript) ? value.transcript.slice(0, 20_000).map((word) => ({
      sourceId: boundedString(word?.sourceId, 'transcript source ID'),
      text: typeof word?.text === 'string' ? word.text.slice(0, 200) : '',
      startMs: boundedInteger(word?.startMs, 'transcript start', 24 * 60 * 60 * 1000, true),
      endMs: boundedInteger(word?.endMs, 'transcript end', 24 * 60 * 60 * 1000),
      confidence: typeof word?.confidence === 'number' ? Math.max(0, Math.min(1, word.confidence)) : 0,
      ...(typeof word?.speakerId === 'string' ? { speakerId: word.speakerId.slice(0, 100) } : {}),
    })) : [],
    observations: Array.isArray(value.observations) ? value.observations.slice(0, 10_000) : [],
    clarifications: Array.isArray(value.clarifications)
      ? value.clarifications.slice(-10).map((item) => {
          if (typeof item !== 'string' || !item.trim() || item.length > 500) {
            throw orchestrationError('Invalid user clarification.', 400, 'invalid_clarification');
          }
          return item.trim();
        })
      : [],
    supportedContractVersions: {
      agentRuns: [2], activityEvents: [2], videoToolRequests: [2], editPrograms: [2],
    },
  };
  if (value.mediaConsent?.mode !== 'video_analysis' ||
      typeof value.mediaConsent.grantedAt !== 'string' || !Number.isFinite(Date.parse(value.mediaConsent.grantedAt)) ||
      typeof value.mediaConsent.disclosureVersion !== 'string') {
    throw orchestrationError('Selective media consent is required.', 400, 'consent_required');
  }
  inventory.mediaConsent = {
    mode: 'video_analysis',
    grantedAt: new Date(value.mediaConsent.grantedAt).toISOString(),
    disclosureVersion: boundedString(value.mediaConsent.disclosureVersion, 'disclosure version'),
  };
  if (!inventory.sources.length) throw orchestrationError('Project inventory requires at least one source.');
  if (new Set(inventory.sources.map((source) => source.id)).size !== inventory.sources.length) {
    throw orchestrationError('Source IDs must be unique.');
  }
  const transcriptSources = new Map(inventory.sources.map((source) => [source.id, source.durationMs]));
  if (inventory.transcript.some((word) => !transcriptSources.has(word.sourceId) || word.endMs > transcriptSources.get(word.sourceId) || word.endMs <= word.startMs)) {
    throw orchestrationError('Transcript timestamps must belong to their source.', 400, 'invalid_transcript');
  }
  const requested = value.budgets || {};
  inventory.budgets = {
    maximumBytes: boundedInteger(requested.maximumBytes ?? DEFAULT_BUDGETS.maximumBytes, 'byte budget', 100 * 1024 * 1024),
    maximumDurationMs: boundedInteger(requested.maximumDurationMs ?? DEFAULT_BUDGETS.maximumDurationMs, 'duration budget', 3 * 60 * 60 * 1000),
    retentionMs: boundedInteger(requested.retentionMs ?? DEFAULT_BUDGETS.retentionMs, 'retention budget', 24 * 60 * 60 * 1000),
  };
  return inventory;
}

async function evidenceFor(inventory, principal, derivativeJobs) {
  return Promise.all(inventory.evidenceJobIds.map(async (jobId) => {
    const job = await derivativeJobs.read(jobId, principal, {
      projectId: inventory.projectId,
      deviceSessionId: inventory.deviceSessionId,
    });
    if (job.status !== 'completed') {
      throw orchestrationError('Evidence is not ready.', 409, 'evidence_not_ready');
    }
    const derivative = derivativeJobs.readCompletedBytes(jobId, principal, {
      projectId: inventory.projectId, deviceSessionId: inventory.deviceSessionId,
    });
    return {
      id: job.id,
      sourceId: job.timelineReferences[0].sourceId,
      startMs: job.timelineReferences[0].startMs,
      endMs: job.timelineReferences[0].endMs,
      resolution: job.resolution || 'LOW',
      contentKey: job.contentKey,
      bytes: derivative.bytes,
      providerFile: job.providerFile?.expiresAt > Date.now() ? job.providerFile : null,
      persistProviderFile: (value) => derivativeJobs.setProviderFile(jobId, principal, value, {
        projectId: inventory.projectId, deviceSessionId: inventory.deviceSessionId,
      }),
    };
  }));
}

function validateVideoToolCall(call, inventory, evidence) {
  if (call?.name !== 'inspect_video_range' || !call.args || typeof call.args !== 'object') {
    throw orchestrationError('Planner requested an unsupported tool.', 422, 'unsupported_tool_call');
  }
  const request = call.args;
  const sourceById = new Map(inventory.sources.map((source) => [source.id, source]));
  const source = sourceById.get(request.sourceId);
  if (!source) throw orchestrationError('Planner requested an unknown source.', 422, 'unsupported_video_request');
  const startMs = boundedInteger(request.startMs, 'video start', source.durationMs, true);
  const endMs = boundedInteger(request.endMs, 'video end', source.durationMs);
  if (endMs <= startMs || endMs - startMs > MAX_VIDEO_RANGE_MS) {
    throw orchestrationError('Video inspection ranges must be between 1 ms and 30 seconds.', 422, 'invalid_video_range');
  }
  const resolution = request.resolution;
  if (!['LOW', 'HIGH'].includes(resolution)) throw orchestrationError('Invalid video resolution.', 422, 'invalid_video_resolution');
  const uncertainty = typeof request.uncertainty === 'string' ? request.uncertainty.trim().slice(0, 500) : '';
  if (resolution === 'HIGH') {
    const overlapsLow = evidence.some((item) => item.sourceId === source.id && item.resolution === 'LOW' &&
      item.startMs < endMs && item.endMs > startMs);
    if (!overlapsLow || !uncertainty) {
      throw orchestrationError('High-resolution inspection requires an overlapping low-resolution inspection and an uncertainty explanation.', 422, 'high_resolution_not_grounded');
    }
  }
  return { source, sourceId: source.id, startMs, endMs, resolution, uncertainty };
}

function validateObservations(items, inventory, evidence) {
  if (!Array.isArray(items)) throw orchestrationError('Gemini observations are required.', 422, 'invalid_observations');
  const sources = new Map(inventory.sources.map((source) => [source.id, source]));
  const seen = new Set(inventory.observations.map((item) => item.id));
  return items.map((item) => {
    const source = sources.get(item?.sourceId);
    if (!source || typeof item.id !== 'string' || !/^[A-Za-z0-9._:-]{1,200}$/.test(item.id) || seen.has(item.id) ||
        !Number.isSafeInteger(item.startMs) || !Number.isSafeInteger(item.endMs) || item.startMs < 0 ||
        item.endMs <= item.startMs || item.endMs > source.durationMs || typeof item.description !== 'string' ||
        typeof item.confidence !== 'number' || item.confidence < 0 || item.confidence > 1 || !['LOW', 'HIGH'].includes(item.resolution)) {
      throw orchestrationError('Gemini returned invalid or duplicate observations.', 422, 'invalid_observations');
    }
    seen.add(item.id);
    const origin = evidence.find((clip) => clip.sourceId === item.sourceId && clip.startMs <= item.startMs && clip.endMs >= item.endMs);
    if (!origin) throw orchestrationError('Gemini observation is outside supplied video.', 422, 'ungrounded_observation');
    return { ...item, description: item.description.slice(0, 500), originatingJobId: origin.id };
  });
}

function validateEditProgram(decision, inventory, observations) {
  if (decision.expectedRevision !== inventory.projectRevision || !Array.isArray(decision.operations)) {
    throw orchestrationError('Invalid edit program envelope.', 422, 'invalid_edit_program');
  }
  const evidenceIds = new Set(observations.map((item) => item.id));
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
    if (operation.type === 'remove_fumbles') validateContextualSpeechDecision(operation, evidenceIds);
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
  const added = validateObservations(decision.observations, inventory, evidence);
  const observations = [...inventory.observations, ...added];
  if (decision.type === 'edit_program') return { ...validateEditProgram(decision, inventory, observations), observations: added };
  if (decision.type === 'clarification') {
    if (typeof decision.question !== 'string' || !decision.question.trim() || decision.question.length > 500) {
      throw orchestrationError('Invalid clarification.', 502, 'invalid_planner_response');
    }
    return { type: 'clarification', question: decision.question, options: Array.isArray(decision.options) ? decision.options.slice(0, 5) : [], observations: added };
  }
  if (decision.type === 'fallback') {
    return { type: 'fallback', reason: typeof decision.reason === 'string' ? decision.reason.slice(0, 1000) : 'Insufficient evidence.', observations: added };
  }
  throw orchestrationError('Planner returned an unsupported decision.', 502, 'invalid_planner_response');
}

class Layer2Orchestrator {
  constructor({ derivativeJobs, decide, repair = decide?.repair }) {
    if (!derivativeJobs || typeof decide !== 'function') throw new TypeError('Layer2Orchestrator dependencies are required.');
    this.derivativeJobs = derivativeJobs;
    this.decide = decide;
    this.repair = repair;
  }

  async authorizeVideo(principal, inventory, request, selectionReason = 'agent_requested') {
    const key = `${request.source.id}:${request.startMs}:${request.endMs}:${request.resolution}`;
    const authorized = await this.derivativeJobs.authorize(principal, {
      projectId: inventory.projectId, deviceSessionId: inventory.deviceSessionId,
      purpose: 'video-inspection', type: 'video', acceptedFormat: 'video/mp4',
      maximumBytes: inventory.budgets.maximumBytes,
      maximumDurationMs: request.endMs - request.startMs, maximumCount: 1,
      mediaMode: 'video_analysis', consent: inventory.mediaConsent, selectionReason,
      retentionMs: inventory.budgets.retentionMs, resolution: request.resolution,
      providerDisclosures: [{ provider: 'google-gemini', purpose: 'multimodal-planning', dataClasses: ['video'] }],
      idempotencyKey: `video:${inventory.projectRevision}:${Buffer.from(key).toString('base64url').slice(0, 120)}`,
      sourceFingerprint: request.source.fingerprint,
      timelineReferences: [{ sourceId: request.source.id, startMs: request.startMs, endMs: request.endMs }],
    });
    return {
      id: authorized.job.id, sourceId: request.source.id, type: 'video', purpose: 'video-inspection',
      acceptedFormat: 'video/mp4', range: { startMs: request.startMs, endMs: request.endMs },
      maximumBytes: inventory.budgets.maximumBytes, maximumDurationMs: request.endMs - request.startMs,
      resolution: request.resolution, uncertainty: request.uncertainty || '',
      expiresAt: new Date(authorized.job.expiresAt).toISOString(), reused: authorized.reused,
    };
  }

  async bootstrap(principal, payload) {
    const inventory = validateInventory(payload);
    if (inventory.evidenceJobIds.length || inventory.sources.length !== 1 || inventory.sources[0].durationMs > MAX_VIDEO_RANGE_MS) return null;
    const source = inventory.sources[0];
    const job = await this.authorizeVideo(principal, inventory, {
      source, startMs: 0, endMs: source.durationMs, resolution: 'LOW', uncertainty: '',
    }, 'request_selected');
    return { type: 'tool_request', tool: 'inspect_video_range', jobs: [job], bootstrap: true };
  }

  async orchestrate(principal, payload) {
    const inventory = validateInventory(payload);
    const evidence = await evidenceFor(inventory, principal, this.derivativeJobs);
    const raw = await this.decide({
      systemPolicy: SYSTEM_POLICY,
      inventory,
      evidence,
    });
    if (raw.toolCall) {
      const request = validateVideoToolCall(raw.toolCall, inventory, evidence);
      const job = await this.authorizeVideo(principal, inventory, request);
      if (job.reused && inventory.evidenceJobIds.includes(job.id)) {
        return { type: 'fallback', reason: 'The requested video range is already attached.', observations: [], _usage: [raw.usage] };
      }
      return { type: 'tool_request', tool: 'inspect_video_range', jobs: [job], _usage: [raw.usage] };
    }
    const recovered = await recoverProposal({
      primary: raw,
      repair: this.repair,
      validate: (value) => validateDecision(value, inventory, evidence),
      originalRequest: inventory,
      editorialIntent: inventory.intent,
    });
    const decision = recovered.decision;
    const usage = recovered.usage || [];
    return {
      ...decision,
      ...(recovered.recovery ? { _recovery: recovered.recovery } : {}),
      ...(usage.length ? { _usage: usage } : {}),
    };
  }
}

module.exports = {
  Layer2Orchestrator, SYSTEM_POLICY, validateDecision, validateInventory, validateVideoToolCall,
};
