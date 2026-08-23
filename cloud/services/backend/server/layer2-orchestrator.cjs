'use strict';

const { validateContextualSpeechDecision } = require('./contextual-speech.cjs');
const ALLOWED_OPERATIONS = new Set([
  'remove_ranges', 'set_color_grade', 'set_audio_levels',
  'add_sound_effect', 'set_background_music', 'add_captions', 'set_aspect_ratio', 'add_text_overlay'
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
const SYSTEM_POLICY = `You are trimm's private video-editing agent.
Follow the latest user message while retaining relevant context from earlier messages.
You must call one supplied tool to proceed.
Use proposal tools for operations they cover; their descriptions contain the operation-specific editorial policy.
Proposal tools build a private draft. After every successful tool result, continue until all necessary operations are present, then call finalize_edit_program.
You may call multiple proposal tools simultaneously if they are independent. If an operation depends on the outcome of another (e.g., inspecting video before editing), call them sequentially over multiple steps.
Call ask_clarification when a consequential user choice is unresolved. Never guess merely to avoid asking.
Use inspect_video_range when more audiovisual evidence is needed. Each range is at most 30 seconds. Use LOW normally. Use HIGH only after overlapping LOW evidence left a specific uncertainty.
Never request rendered output, executable instructions, or provider credentials.
If the transcript is empty, assume there is no speech in the video and focus exclusively on visual, structural, and background audio edits.
Every edit operation must cite supplied evidence IDs and include confidence from 0 to 1.
Do not invent evidence. When evidence is missing or uncertain, request bounded evidence, clarify, or fall back.
Use fine tools: one effect, one exact range, and one bounded parameter set per call. Never ask a tool to scan or choose its own settings.
Supported rendered operations: remove_ranges, set_color_grade, set_audio_levels, add_captions, set_aspect_ratio, add_text_overlay.

When the user asks for a native text overlay, title card, or animated graphics containing text (e.g. "comic book BANG effect"), use the propose_add_text_overlay tool.

When the user asks to change the video's aspect ratio (e.g. "vertical", "portrait", "landscape", "square", or "16:9"), use the propose_set_aspect_ratio tool with the appropriate ratio (PORTRAIT, LANDSCAPE, or SQUARE).

Examples:
User asks to remove a pause but no covering video is attached -> call inspect_video_range.
User asks to make pacing better without a settled pacing preference -> call ask_clarification with Natural and Tight choices.
User asks to make the video vertical -> call propose_set_aspect_ratio with ratio="PORTRAIT".
A tool rejects an invented evidence ID -> correct the call using only supplied IDs; never fabricate a replacement.`;

const PROPOSAL_TOOL_TYPES = Object.freeze({
  propose_remove_ranges: 'remove_ranges',
  propose_set_dialogue_loudness: 'set_audio_levels',
  propose_set_music_ducking: 'set_audio_levels',
  propose_add_captions: 'add_captions',
  propose_adjust_exposure: 'set_color_grade',
  propose_adjust_temperature: 'set_color_grade',
  propose_adjust_contrast: 'set_color_grade',
  propose_adjust_saturation: 'set_color_grade',
  propose_adjust_highlights: 'set_color_grade',
  propose_adjust_shadows: 'set_color_grade',
  propose_adjust_skin_protection: 'set_color_grade',
  propose_set_aspect_ratio: 'set_aspect_ratio',
  propose_add_text_overlay: 'add_text_overlay',
});

function fineOperationFor(call, operationType, inventory) {
  const args = call.args || {};
  const raw = args.operation || {};
  const target = raw.sourceId ? {
    sourceId: raw.sourceId, startMs: raw.startMs, endMs: raw.endMs,
  } : undefined;
  const common = { ...raw, type: operationType, ...(target ? { target } : {}) };
  const sourceIndex = inventory.sources.findIndex((source) => source.id === raw.sourceId);
  const sourceOffset = sourceIndex < 0 ? 0 : inventory.sources.slice(0, sourceIndex)
    .reduce((total, source) => total + source.durationMs, 0);

  if (call.name === 'propose_set_dialogue_loudness') {
    return { ...common, parameter: 'dialogue_loudness', dialogueLufs: raw.targetLufs };
  }
  if (call.name === 'propose_set_music_ducking') {
    return { ...common, parameter: 'music_ducking', musicDuckDb: raw.duckDb };
  }
  if (call.name === 'propose_add_captions') {
    const operation = { ...common, cues: [] };
    if (raw.sourceId) {
      operation.target = { sourceId: raw.sourceId, startMs: raw.startMs, endMs: raw.endMs };
    }
    if (raw.speakerId) {
      operation.speakerId = raw.speakerId;
    }
    return operation;
  }
  if (call.name.startsWith('propose_adjust_')) {
    const parameter = call.name.slice('propose_adjust_'.length).replace(/_([a-z])/g, (_, letter) => letter.toUpperCase());
    return { ...common, parameter, value: raw.value };
  }
  if (call.name === 'propose_set_aspect_ratio') {
    return { ...common, ratio: raw.ratio };
  }
  if (call.name === 'propose_add_text_overlay') {
    return { 
      ...common, 
      text: raw.text, 
      description: raw.description, 
      designBlueprint: raw.designBlueprint
    };
  }

  return common;
}

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

function rejectPrivateOrExecutable(value, isPlanner = false) {
  if (!value || typeof value !== 'object') return;
  for (const [key, child] of Object.entries(value)) {
    if (FORBIDDEN_KEYS.has(key)) {
      if (isPlanner) throw orchestrationError('Planner returned forbidden fields.', 502, 'invalid_planner_response');
      else throw orchestrationError('Private or executable planner fields are forbidden.', 400, 'cloud_boundary_violation');
    }
    rejectPrivateOrExecutable(child, isPlanner);
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
    agentDraft: value.agentDraft && Array.isArray(value.agentDraft.operations) ? {
      operations: value.agentDraft.operations.slice(0, 200),
    } : null,
    clarifications: Array.isArray(value.clarifications)
      ? value.clarifications.slice(-10).map((item) => {
        if (typeof item !== 'string' || !item.trim() || item.length > 500) {
          throw orchestrationError('Invalid user clarification.', 400, 'invalid_clarification');
        }
        return item.trim();
      })
      : [],
    messages: Array.isArray(value.messages) ? value.messages.slice(-30).map((message) => {
      if (!message || !['user', 'assistant'].includes(message.role) || typeof message.content !== 'string' ||
        !message.content.trim() || message.content.length > 4000) {
        throw orchestrationError('Invalid conversation message.', 400, 'invalid_conversation');
      }
      return { role: message.role, content: message.content.trim() };
    }) : [],
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
    const derivative = await derivativeJobs.readCompletedBytes(jobId, principal, {
      projectId: inventory.projectId, deviceSessionId: inventory.deviceSessionId,
    });
    return {
      id: job.id,
      sourceId: job.timelineReferences[0].sourceId,
      startMs: job.timelineReferences[0].startMs,
      endMs: job.timelineReferences[0].endMs,
      resolution: job.resolution || 'LOW',
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
  const maxAllowed = Math.min(MAX_VIDEO_RANGE_MS, inventory.budgets.maximumDurationMs);
  if (endMs <= startMs || endMs - startMs > maxAllowed) {
    throw orchestrationError(`Video inspection ranges must be between 1 ms and ${maxAllowed / 1000} seconds.`, 422, 'invalid_video_range');
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

function transcriptTextForProjectRange(inventory, startMs, endMs) {
  let offset = 0;
  const words = [];
  for (const source of inventory.sources) {
    const localStart = Math.max(0, startMs - offset);
    const localEnd = Math.min(source.durationMs, endMs - offset);
    if (localEnd > localStart) {
      words.push(...inventory.transcript.filter((word) => word.sourceId === source.id &&
        word.startMs < localEnd && word.endMs > localStart).sort((a, b) => a.startMs - b.startMs).map((word) => word.text));
    }
    offset += source.durationMs;
  }
  return words.join(' ').replace(/\s+/g, ' ').trim().toLocaleLowerCase();
}

function validateEditProgram(decision, inventory, observations) {
  if (decision.expectedRevision !== inventory.projectRevision || !Array.isArray(decision.operations)) {
    throw orchestrationError('Invalid edit program envelope.', 422, 'invalid_edit_program');
  }
  const evidenceIds = new Set([...observations.map((item) => item.id), 'transcript:full', 'transcript']);
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

    if (operation.target && ['set_color_grade', 'set_audio_levels',
      'add_captions'].includes(operation.type)) {
      const target = operation.target;
      const source = inventory.sources.find((item) => item.id === target?.sourceId);
      if (!source || !Number.isSafeInteger(target.startMs) || !Number.isSafeInteger(target.endMs) ||
        target.startMs < 0 || target.endMs <= target.startMs || target.endMs > source.durationMs) {
        throw orchestrationError('Fine edit target is outside its source.', 422, 'invalid_edit_target');
      }
      const covered = observations.some((item) => operation.evidenceIds.includes(item.id) &&
        item.sourceId === target.sourceId && item.startMs <= target.startMs && item.endMs >= target.endMs);
      if (!covered) throw orchestrationError('Fine edit target is not covered by cited evidence.', 422, 'invalid_evidence_reference');
      if (typeof operation.reason !== 'string' || !operation.reason.trim()) {
        throw orchestrationError('Fine edit requires a reason.', 422, 'invalid_edit_program');
      }
    }
    if (['remove_ranges'].includes(operation.type)) {
      const sources = new Map(inventory.sources.map((source) => [source.id, source]));
      if (!Array.isArray(operation.ranges) || !operation.ranges.length) {
        throw orchestrationError('Speech and silence removals require bounded ranges.', 422, 'invalid_edit_program');
      }
      for (const range of operation.ranges) {
        const source = sources.get(range?.sourceId);
        if (!source || !Number.isSafeInteger(range.startMs) || !Number.isSafeInteger(range.endMs) ||
          range.startMs < 0 || range.endMs <= range.startMs || range.endMs > source.durationMs ||
          !Array.isArray(range.evidenceIds) || !range.evidenceIds.length ||
          range.evidenceIds.some((id) => !evidenceIds.has(id))) {
          throw orchestrationError('Removal range is outside its source or lacks evidence.', 422, 'invalid_edit_program');
        }
        const coversRange = observations.some((item) => range.evidenceIds.includes(item.id) &&
          item.sourceId === range.sourceId && item.startMs <= range.startMs && item.endMs >= range.endMs);
        if (!coversRange) {
          throw orchestrationError('Removal range is not covered by cited evidence.', 422, 'invalid_evidence_reference');
        }
      }
    }
    if (operation.type === 'set_color_grade') {
      const bounds = {
        exposure: [-1, 1], temperature: [-1, 1], contrast: [.75, 1.35],
        saturation: [.7, 1.3], highlights: [-1, 1], shadows: [-1, 1], skinProtection: [0, 1]
      };
      const limit = bounds[operation.parameter];
      const grade = operation.grade;
      const legacyGrade = grade && typeof grade === 'object' &&
        [['exposure', -1, 1], ['temperature', -1, 1], ['contrast', .75, 1.35], ['saturation', .7, 1.3],
        ['highlights', -1, 1], ['shadows', -1, 1], ['skinProtection', 0, 1]]
          .every(([key, minimum, maximum]) => typeof grade[key] === 'number' && grade[key] >= minimum && grade[key] <= maximum);
      if (!legacyGrade && (!limit || typeof operation.value !== 'number' || operation.value < limit[0] || operation.value > limit[1])) {
        throw orchestrationError('Color adjustment is outside renderer-safe bounds.', 422, 'invalid_edit_program');
      }
    }

    if (operation.type === 'set_audio_levels') {
      const validDialogue = operation.parameter === 'dialogue_loudness' && typeof operation.dialogueLufs === 'number' &&
        operation.dialogueLufs >= -24 && operation.dialogueLufs <= -12;
      const validDucking = operation.parameter === 'music_ducking' && typeof operation.musicDuckDb === 'number' &&
        operation.musicDuckDb >= -18 && operation.musicDuckDb <= -3 && Number.isSafeInteger(operation.attackMs) &&
        operation.attackMs >= 0 && operation.attackMs <= 2000 && Number.isSafeInteger(operation.releaseMs) &&
        operation.releaseMs >= 0 && operation.releaseMs <= 5000;
      const legacy = operation.parameter == null && typeof operation.dialogueLufs === 'number' &&
        operation.dialogueLufs >= -24 && operation.dialogueLufs <= -12 && typeof operation.musicDuckDb === 'number' &&
        operation.musicDuckDb >= -18 && operation.musicDuckDb <= -3;
      if (!validDialogue && !validDucking && !legacy) throw orchestrationError('Audio level operation is invalid.', 422, 'invalid_edit_program');
    }
    if (operation.type === 'add_sound_effect' &&
      (!Number.isSafeInteger(operation.atSourceMs) || operation.atSourceMs < 0 ||
        (operation.target && (operation.atSourceMs !== inventory.sources.slice(0,
          inventory.sources.findIndex((source) => source.id === operation.target.sourceId))
          .reduce((total, source) => total + source.durationMs, operation.target.startMs) ||
          !Number.isSafeInteger(operation.durationMs) || operation.durationMs < 1)) ||
        typeof operation.catalogId !== 'string' || !operation.catalogId ||
        typeof operation.gainDb !== 'number' || operation.gainDb < -36 || operation.gainDb > 0)) {
      throw orchestrationError('Sound effect parameters are invalid.', 422, 'invalid_edit_program');
    }
    if (operation.type === 'set_background_music' &&
      (typeof operation.catalogId !== 'string' || !operation.catalogId ||
        typeof operation.gainDb !== 'number' || operation.gainDb < -40 || operation.gainDb > -6 ||
        typeof operation.loop !== 'boolean' || (operation.target && (!Number.isSafeInteger(operation.fadeInMs) || operation.fadeInMs < 0 ||
          !Number.isSafeInteger(operation.fadeOutMs) || operation.fadeOutMs < 0 ||
          operation.fadeInMs + operation.fadeOutMs > operation.target.endMs - operation.target.startMs)))) {
      throw orchestrationError('Background music parameters are invalid.', 422, 'invalid_edit_program');
    }
    if (operation.type === 'add_captions') {
      if (!Array.isArray(operation.cues) || operation.style !== 'bold-bottom' ||
        operation.cues.some((cue) => typeof cue?.text !== 'string' || !cue.text.trim() || cue.text.length > 120 ||
          !Number.isSafeInteger(cue.range?.startMs) || !Number.isSafeInteger(cue.range?.endMs) ||
          cue.range.startMs < 0 || cue.range.endMs <= cue.range.startMs ||
          !transcriptTextForProjectRange(inventory, cue.range.startMs, cue.range.endMs)
            .includes(cue.text.replace(/\s+/g, ' ').trim().toLocaleLowerCase()))) {
        throw orchestrationError('Captions must be bounded and grounded in the supplied transcript.', 422, 'invalid_edit_program');
      }
    }

    if (operation.type === 'set_aspect_ratio') {
      if (typeof operation.ratio !== 'string' || !['PORTRAIT', 'LANDSCAPE', 'SQUARE'].includes(operation.ratio)) {
        throw orchestrationError('Aspect ratio must be PORTRAIT, LANDSCAPE, or SQUARE.', 422, 'invalid_edit_program');
      }
    }
    if (operation.type === 'add_text_overlay') {
      if (typeof operation.text !== 'string' || !operation.text.trim()) {
        throw orchestrationError('Text overlay requires text.', 422, 'invalid_edit_program');
      }
      if (typeof operation.designBlueprint !== 'string' || !operation.designBlueprint.trim()) {
        throw orchestrationError('Text overlay requires a designBlueprint.', 422, 'invalid_edit_program');
      }
    }

  }
  for (const operation of decision.operations.filter((item) => item.type === 'set_color_grade' && item.parameter === 'skinProtection')) {
    const companion = decision.operations.some((item) => item.type === 'set_color_grade' && item.id !== operation.id &&
      item.parameter !== 'skinProtection' && item.target?.sourceId === operation.target?.sourceId &&
      item.target?.startMs < operation.target?.endMs && item.target?.endMs > operation.target?.startMs);
    if (!companion) throw orchestrationError('Skin protection requires an overlapping color adjustment.', 422, 'invalid_edit_program');
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
  rejectPrivateOrExecutable(decision, true);
  const added = validateObservations(decision.observations, inventory, evidence);
  const observations = [...inventory.observations, ...added];
  if (decision.type === 'edit_program') return { ...validateEditProgram(decision, inventory, observations), observations: added };
  if (decision.type === 'clarification') {
    const questions = Array.isArray(decision.questions) ? decision.questions.slice(0, 3) : [];
    if (!questions.length || questions.some((question) => typeof question?.id !== 'string' ||
      typeof question?.prompt !== 'string' || !question.prompt.trim() || question.prompt.length > 500 ||
      !Array.isArray(question.options) || question.options.length < 2 || question.options.length > 4)) {
      throw orchestrationError('Invalid clarification.', 502, 'invalid_planner_response');
    }
    return { type: 'clarification', questions, question: questions[0].prompt, options: questions[0].options, observations: added };
  }
  if (decision.type === 'fallback') {
    return { type: 'fallback', reason: typeof decision.reason === 'string' ? decision.reason.slice(0, 1000) : 'Insufficient evidence.', observations: added };
  }
  throw orchestrationError('Planner returned an unsupported decision.', 502, 'invalid_planner_response');
}

class Layer2Orchestrator {
  constructor({ derivativeJobs, decide }) {
    if (!derivativeJobs || typeof decide !== 'function') throw new TypeError('Layer2Orchestrator dependencies are required.');
    this.derivativeJobs = derivativeJobs;
    this.decide = decide;
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
      id: authorized.job.id, toolCallId: authorized.job.id, sourceId: request.source.id, type: 'video', purpose: 'video-inspection',
      acceptedFormat: 'video/mp4', range: { startMs: request.startMs, endMs: request.endMs },
      maximumBytes: inventory.budgets.maximumBytes, maximumDurationMs: request.endMs - request.startMs,
      resolution: request.resolution, uncertainty: request.uncertainty || '',
      expiresAt: new Date(authorized.job.expiresAt).toISOString(), reused: authorized.reused,
    };
  }

  async bootstrap(principal, payload) {
    const inventory = validateInventory(payload);
    if (inventory.evidenceJobIds.length) return null;
    const source = inventory.sources[0];
    const maxAllowed = Math.min(MAX_VIDEO_RANGE_MS, inventory.budgets.maximumDurationMs);
    const job = await this.authorizeVideo(principal, inventory, {
      source, startMs: 0, endMs: Math.min(source.durationMs, maxAllowed), resolution: 'LOW', uncertainty: '',
    }, 'request_selected');
    return { type: 'tool_request', tool: 'inspect_video_range', jobs: [job], bootstrap: true };
  }

  async orchestrate(principal, payload) {
    const inventory = validateInventory(payload);
    const evidence = await evidenceFor(inventory, principal, this.derivativeJobs);
    let workingInventory = inventory;
    let raw = await this.decide({ systemPolicy: SYSTEM_POLICY, inventory: workingInventory, evidence });
    const usage = [];
    const drafts = new Map((workingInventory.agentDraft?.operations || []).map((operation) => [operation.id, operation]));
    const draftObservations = [];
    for (let iteration = 0; iteration < 8; iteration++) {
      usage.push(raw.usage);
      usage.push(raw.usage);
      const calls = raw.toolCalls || (raw.toolCall ? [raw.toolCall] : []);

      if (calls.length === 0) {
        if (typeof this.decide !== 'function') throw orchestrationError('Planner returned no tool calls.', 502, 'invalid_planner_response');
        raw = await this.decide({
          systemPolicy: SYSTEM_POLICY, inventory: workingInventory, evidence,
          history: [...(raw.history || []), { role: 'user', parts: [{ text: `Agent run failure: Planner returned no tool calls.` }] }]
        });
        continue;
      }

      let yieldDecision = null;
      const results = [];

      for (const call of calls) {
        if (call.name === 'inspect_video_range') {
          try {
            const request = validateVideoToolCall(call, workingInventory, evidence);
            const job = await this.authorizeVideo(principal, workingInventory, request);
            if (job.reused && workingInventory.evidenceJobIds.includes(job.id)) {
              yieldDecision = { type: 'fallback', reason: 'The requested video range is already attached.', observations: [], _usage: usage };
              break;
            }
            yieldDecision = {
              type: 'tool_request', tool: 'inspect_video_range', jobs: [job], _usage: usage,
              _agentState: { operations: [...drafts.values()], observations: draftObservations },
            };
            break;
          } catch (error) {
            results.push({ ok: false, code: error.code || 'invalid_tool_arguments', message: String(error.message).slice(0, 300) });
            continue;
          }
        }

        if (call.name === 'ask_clarification') {
          try {
            const questions = Array.isArray(call.args?.questions) ? call.args.questions.slice(0, 3) : [];
            if (!questions.length) throw orchestrationError('Clarification questions are required.', 422, 'invalid_clarification');
            yieldDecision = {
              type: 'clarification', questions, question: questions[0].prompt, options: questions[0].options,
              observations: [], _usage: usage,
              _agentState: { operations: [...drafts.values()], observations: draftObservations },
            };
            break;
          } catch (error) {
            results.push({ ok: false, code: error.code || 'invalid_tool_arguments', message: String(error.message).slice(0, 300) });
            continue;
          }
        }

        if (call.name === 'finish_without_proposal') {
          yieldDecision = { type: 'fallback', reason: String(call.args?.reason || 'The edit could not be grounded.').slice(0, 1000), observations: [], _usage: usage };
          break;
        }

        if (call.name === 'finalize_edit_program') {
          try {
            const ids = Array.isArray(call.args?.operationIds) ? call.args.operationIds : [];
            if (ids.some((id) => !drafts.has(id)) || new Set(ids).size !== ids.length) {
              throw orchestrationError('Finalization referenced an unknown draft operation.', 422, 'invalid_edit_program');
            }
            const decision = validateDecision({
              type: 'edit_program', expectedRevision: call.args.expectedRevision,
              summary: call.args.summary, operations: ids.map((id) => drafts.get(id)), observations: [],
            }, workingInventory, evidence);
            yieldDecision = { ...decision, _usage: usage };
            break;
          } catch (error) {
            results.push({ ok: false, code: error.code || 'invalid_tool_arguments', message: String(error.message).slice(0, 300) });
            continue;
          }
        }

        if (call.name === 'remove_draft_operation') {
          const operationId = call.args?.operationId;
          const removed = typeof operationId === 'string' && drafts.delete(operationId);
          results.push(removed ? { ok: true, operationId, draftOperationCount: drafts.size }
            : { ok: false, code: 'unknown_draft_operation', message: 'The requested draft operation does not exist.' });
          continue;
        }

        const operationType = PROPOSAL_TOOL_TYPES[call.name];
        if (!operationType) {
          results.push({ ok: false, code: 'unsupported_tool_call', message: 'Planner requested an unsupported tool.' });
          continue;
        }

        try {
          const args = call.args || {};
          
          if (call.name === 'propose_add_text_overlay') {
             if (typeof this.decide.generateTextOverlayConfig !== 'function') throw orchestrationError('Text overlay subagent is not available.', 501, 'unsupported_tool_call');
             const rawOp = args.operation || {};
             const configResult = await this.decide.generateTextOverlayConfig({
               description: rawOp.description,
               text: rawOp.text
             });
             args.operation = { 
               ...rawOp, 
               designBlueprint: JSON.stringify(configResult)
             };
          }

          const operation = fineOperationFor(call, operationType, workingInventory);
          const validated = validateDecision({
            type: 'edit_program', expectedRevision: args.expectedRevision,
            summary: args.summary, operations: [operation], observations: args.observations,
          }, workingInventory, evidence);
          const accepted = validated.operations[0];
          drafts.set(accepted.id, accepted);
          workingInventory = {
            ...workingInventory,
            observations: [...workingInventory.observations, ...(validated.observations || [])],
          };
          draftObservations.push(...(validated.observations || []));
          results.push({ ok: true, operationId: accepted.id, operationType, draftOperationCount: drafts.size });
        } catch (error) {
          results.push({ ok: false, code: error.code || 'invalid_tool_arguments', message: String(error.message).slice(0, 300) });
        }
      }

      if (yieldDecision) {
        return yieldDecision;
      }

      if (typeof this.decide.toolResults !== 'function' && typeof this.decide.toolResult !== 'function') {
        throw orchestrationError('The model provider cannot continue after tool execution.', 500, 'agent_loop_unavailable');
      }
      
      // Fallback for single tool result if toolResults isn't implemented (tests might only mock decide.toolResult)
      if (typeof this.decide.toolResults === 'function') {
        raw = await this.decide.toolResults({
          systemPolicy: SYSTEM_POLICY, inventory: workingInventory, evidence,
          history: raw.history, toolCalls: calls, results,
        });
      } else {
        raw = await this.decide.toolResult({
          systemPolicy: SYSTEM_POLICY, inventory: workingInventory, evidence,
          history: raw.history, toolCall: calls[0], result: results[0],
        });
      }
    }
    return { type: 'fallback', reason: 'The bounded editing loop reached its iteration limit.', observations: [], _usage: usage };
  }
}

module.exports = {
  Layer2Orchestrator, SYSTEM_POLICY, validateDecision, validateInventory, validateVideoToolCall,
};
