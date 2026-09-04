
const SCOPES = new Set(['exact', 'broad_editorial', 'specialist']);
const REASONS = new Set([
  'user_requested', 'representative_coverage', 'measured_candidate',
  'uncertainty', 'agent_requested',
]);
const DERIVATIVES = new Set(['audio', 'frame', 'crop', 'text', 'evidence']);
const SIGNALS = new Set(['speech', 'sound', 'visual', 'motion', 'text', 'technical']);

function invalid(message) {
  return Object.assign(new Error(message), { status: 400, code: 'invalid_media_index' });
}

function text(value, name) {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9._:-]{1,200}$/.test(value)) throw invalid(`Invalid ${name}.`);
  return value;
}

function range(value, durationMs, name) {
  if (!value || !Number.isSafeInteger(value.startMs) || !Number.isSafeInteger(value.endMs) ||
      value.startMs < 0 || value.endMs <= value.startMs || value.endMs > durationMs) {
    throw invalid(`Invalid ${name}.`);
  }
  return { startMs: value.startMs, endMs: value.endMs };
}

function enumList(value, allowed, name) {
  if (!Array.isArray(value) || !value.length || value.some((item) => !allowed.has(item))) {
    throw invalid(`Invalid ${name}.`);
  }
  return [...new Set(value)];
}

function validateMediaIndex(value, inventorySources) {
  if (!value || value.schemaVersion !== 1 || !SCOPES.has(value.requestScope)) {
    throw invalid('Invalid media index envelope.');
  }
  const sources = new Map(inventorySources.map((source) => [source.id, source.durationMs]));
  const segments = Array.isArray(value.segments) ? value.segments.map((segment) => {
    const sourceId = text(segment.sourceId, 'segment source ID');
    const duration = sources.get(sourceId);
    if (!duration) throw invalid('Media index references an unknown source.');
    if ('label' in segment || 'meaning' in segment || 'storyBeat' in segment) {
      throw invalid('Layer 1 segments must contain observations, not editorial meaning.');
    }
    return {
      id: text(segment.id, 'segment ID'),
      sourceId,
      ...range(segment, duration, 'segment range'),
      signalKinds: enumList(segment.signalKinds, SIGNALS, 'segment signals'),
      observationIds: Array.isArray(segment.observationIds)
        ? segment.observationIds.slice(0, 500).map((id) => text(id, 'observation ID'))
        : [],
    };
  }) : [];
  const selections = Array.isArray(value.selections) ? value.selections.map((selection) => {
    const sourceId = text(selection.sourceId, 'selection source ID');
    const duration = sources.get(sourceId);
    if (!duration) throw invalid('Media index references an unknown source.');
    return {
      id: text(selection.id, 'selection ID'),
      sourceId,
      ...range(selection, duration, 'selection range'),
      reasons: enumList(selection.reasons, REASONS, 'selection reasons'),
      observationIds: Array.isArray(selection.observationIds)
        ? selection.observationIds.slice(0, 500).map((id) => text(id, 'observation ID'))
        : [],
      derivativeKinds: enumList(selection.derivativeKinds, DERIVATIVES, 'derivative kinds'),
    };
  }) : [];
  if (!selections.length) throw invalid('Media index requires at least one selected range.');
  if (value.requestScope === 'broad_editorial' &&
      !selections.some((selection) => selection.reasons.includes('representative_coverage'))) {
    throw invalid('Broad editorial indexes require representative coverage.');
  }
  return {
    schemaVersion: 1,
    indexId: text(value.indexId, 'index ID'),
    requestScope: value.requestScope,
    generatedAtEpochMs: Number.isSafeInteger(value.generatedAtEpochMs) ? value.generatedAtEpochMs : 0,
    segments,
    selections,
  };
}

export {  validateMediaIndex  };
