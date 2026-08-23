'use strict';

const REQUIRED_CONTEXT = new Set([
  'surrounding_context', 'speaker_scene_cadence', 'word_phoneme_timing',
  'user_intent', 'alternate_takes', 'waveform_scene_noise', 'surrounding_video',
  'proposal_simulation',
]);
const UNCERTAINTY_ACTIONS = new Set(['none', 're_listened', 'alternate_transcription']);
const LOCATOR_SIGNALS = new Set([
  'possible_filler', 'pause_candidate', 'repeated_phrase', 'transcript_uncertainty',
  'delivery_discontinuity',
]);

function contextualError(message) {
  return Object.assign(new Error(message), { status: 422, code: 'contextual_speech_required' });
}

function validateContextualSpeechDecision(operation, evidenceIds) {
  const decision = operation.speechDecision;
  if (!decision || typeof decision !== 'object') {
    throw contextualError('Speech cuts require a contextual editorial decision.');
  }
  if (decision.meaningPreserved !== true || decision.completeDeliverySelected !== true) {
    throw contextualError('Speech cuts must preserve meaning and select a complete delivery.');
  }
  if (typeof decision.rationale !== 'string' || !decision.rationale.trim() || decision.rationale.length > 1000) {
    throw contextualError('Speech cuts require a bounded contextual rationale.');
  }
  if (!Array.isArray(decision.contextSignals) ||
      decision.contextSignals.some((signal) => !REQUIRED_CONTEXT.has(signal))) {
    throw contextualError('Speech-cut context signals are invalid.');
  }
  const signals = new Set(decision.contextSignals);
  for (const required of ['surrounding_context', 'speaker_scene_cadence', 'user_intent', 'proposal_simulation']) {
    if (!signals.has(required)) throw contextualError(`Speech cuts must consider ${required}.`);
  }
  if (!signals.has('word_phoneme_timing') && !signals.has('waveform_scene_noise')) {
    throw contextualError('Speech cuts require timing or scene-aware audio context.');
  }
  if (!signals.has('alternate_takes') && !signals.has('surrounding_video')) {
    throw contextualError('Speech cuts require delivery comparison or surrounding video context.');
  }
  if (!Array.isArray(decision.locatorSignals) ||
      decision.locatorSignals.some((signal) => !LOCATOR_SIGNALS.has(signal))) {
    throw contextualError('Speech candidate locator signals are invalid.');
  }
  if (!UNCERTAINTY_ACTIONS.has(decision.uncertaintyAction)) {
    throw contextualError('Speech uncertainty handling is invalid.');
  }
  if (decision.transcriptUncertain === true && decision.uncertaintyAction === 'none') {
    throw contextualError('Uncertain transcripts must be re-listened or retranscribed.');
  }
  if (!Array.isArray(decision.contextEvidenceIds) || !decision.contextEvidenceIds.length ||
      decision.contextEvidenceIds.some((id) => !evidenceIds.has(id))) {
    throw contextualError('Speech context must cite supplied evidence.');
  }
  if (typeof decision.technicalTieBreakerOnly !== 'boolean') {
    throw contextualError('Speech decisions must state how recording quality was used.');
  }
  return decision;
}

function evaluateSpeechFixture(fixture) {
  const proposed = fixture.decision === 'cut';
  const context = new Set(fixture.contextSignals || []);
  const forbiddenSoleBasis = proposed && (
    fixture.basis?.length === 1 &&
    ['word_list', 'confidence_threshold', 'pause_threshold', 'quality_score'].includes(fixture.basis[0])
  );
  const contextualCut = !proposed || (
    fixture.meaningPreserved === true &&
    fixture.completeDeliverySelected === true &&
    context.has('surrounding_context') &&
    context.has('speaker_scene_cadence') &&
    context.has('user_intent') &&
    context.has('proposal_simulation') &&
    (context.has('word_phoneme_timing') || context.has('waveform_scene_noise')) &&
    (context.has('alternate_takes') || context.has('surrounding_video')) &&
    (!fixture.transcriptUncertain || ['re_listened', 'alternate_transcription'].includes(fixture.uncertaintyAction))
  );
  return { passed: !forbiddenSoleBasis && contextualCut, forbiddenSoleBasis, contextualCut };
}

module.exports = { validateContextualSpeechDecision, evaluateSpeechFixture };
