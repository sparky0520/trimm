'use strict';

const OpenAI = require('openai').default;
const CREATOR_PREFERENCE_POLICY = `Creator preferences are transparent learned taste only. Current project intent and hard footage evidence override them. Preference provenanceDecisionIds are not footage evidence and must never justify invented operations or timestamps.`;
const SYSTEM_POLICY = `You are ctrlB's conservative mobile video-edit planner. Return one JSON object only. Either request bounded additional evidence with {"type":"evidence_request","query":{"request":string,"evidenceTypes":["STORY_BEAT"|"TRANSCRIPT"|"FRAME"|"VISUAL"|"AUDIO_SUMMARY"|"SHOT"],"sourceIds":[],"ranges":[],"excludeEvidenceIds":[],"budget":object}} or return a proposal. Plan in order: choose story beats and takes first, then CORRECTION, PACING, COVERAGE, and finally OPTIONAL_TREATMENT. A proposal is {"type":"proposal","expectedRevision":number,"summary":string,"plan":{"story":[story choices],"decisions":[decisions in stage order],"operationPlans":[one explanation per operation]},"operations":array,"evidenceIds":array}. Every story choice, decision, and operation explanation requires rationale, nonempty evidenceIds copied from evidence.items, confidence from 0 to 1, assumptions array, and expectedStoryEffect. Decisions also require unique id, stage ("STORY","TAKE_SELECTION","CORRECTION","PACING","COVERAGE","OPTIONAL_TREATMENT"), and kind ("REQUIRED_CLEANUP","OPTIONAL_STYLE","OPTIONAL_MUSIC"). Non-optional stages are REQUIRED_CLEANUP; optional style and music are only OPTIONAL_TREATMENT. Preserve useful existing operations. Every operation requires id, enabled, and one of: remove_fumbles, set_audio_cleanup, set_audio_levels, add_sound_effect, set_background_music, add_captions, semantic_visual. A semantic_visual is optional style with intent INTRODUCE|POINT_OUT|TRANSITION|REVEAL|EMPHASIZE|RECAP, treatment CALLOUT|SPOTLIGHT|REFRAME, a 300-8000ms range, and subject {evidenceId,kind,bounds,confidence}; kind must be FACE|SCREEN_UI|PRODUCT|OCR_TEXT|SALIENCE and evidenceId must cite supplied visual evidence. Use at most two overlaps and three treatments in any ten seconds. Reduced motion uses curve HOLD. REFRAME also requires framing {zoom,preservesSubject:true,preservesText:true}; ask for clarification instead if subject or text preservation is uncertain. Color requires frame evidence and remains unavailable to this planner. Legacy generic transitions and motion presets are unavailable. Never invent timestamps or evidence. Prefer grounded footage-specific edits and make no destructive low-confidence cut. Never add a semantic treatment, music, or sound effect merely to make plans differ; music and sound effects require explicit evidence plus user intent and must remain optional.`;

function validSemanticOperation(operation, validEvidenceIds) {
  if (operation.type !== 'semantic_visual') return true;
  const range = operation.range;
  const subject = operation.subject;
  const motion = operation.motion || {};
  const bounds = subject?.bounds;
  const values = bounds && [bounds.left, bounds.top, bounds.right, bounds.bottom];
  if (!['INTRODUCE', 'POINT_OUT', 'TRANSITION', 'REVEAL', 'EMPHASIZE', 'RECAP'].includes(operation.intent) ||
      !['CALLOUT', 'SPOTLIGHT', 'REFRAME'].includes(operation.treatment) ||
      !range || !Number.isSafeInteger(range.startMs) || !Number.isSafeInteger(range.endMs) ||
      range.startMs < 0 || range.endMs - range.startMs < 300 || range.endMs - range.startMs > 8000 ||
      !subject || !validEvidenceIds.has(subject.evidenceId) ||
      !['FACE', 'SCREEN_UI', 'PRODUCT', 'OCR_TEXT', 'SALIENCE'].includes(subject.kind) ||
      typeof subject.confidence !== 'number' || subject.confidence < .55 || subject.confidence > 1 ||
      !values || values.some((value) => typeof value !== 'number' || value < 0 || value > 1) ||
      bounds.right <= bounds.left || bounds.bottom <= bounds.top ||
      typeof motion.intensity !== 'number' || motion.intensity < 0 || motion.intensity > .7 ||
      !['HOLD', 'EASE_IN_OUT'].includes(motion.curve) || (motion.reducedMotion && motion.curve !== 'HOLD')) return false;
  if (operation.treatment !== 'REFRAME') return operation.framing == null;
  return operation.framing && typeof operation.framing.zoom === 'number' &&
    operation.framing.zoom >= 1 && operation.framing.zoom <= 2.25 &&
    operation.framing.preservesSubject === true && operation.framing.preservesText === true;
}

function validateRequest(value) {
  if (!value || typeof value !== 'object' || typeof value.prompt !== 'string' || value.prompt.length > 2000) return false;
  const creator = value.creatorPreferences || {};
  if (Object.keys(creator).some((key) => !['brandAssets', 'terminology', 'captionRules', 'pacing', 'motionTolerance', 'provenanceDecisionIds'].includes(key)) ||
      ['brandAssets', 'terminology', 'captionRules', 'provenanceDecisionIds'].some((key) =>
        creator[key] != null && (!Array.isArray(creator[key]) || creator[key].length > 20 ||
          creator[key].some((item) => typeof item !== 'string' || item.length > 500))) ||
      (creator.pacing != null && (typeof creator.pacing !== 'string' || creator.pacing.length > 50)) ||
      (creator.motionTolerance != null && (typeof creator.motionTolerance !== 'string' || creator.motionTolerance.length > 50))) return false;
  if (!Array.isArray(value.operations) || !value.evidence || value.evidence.schemaVersion !== 1 ||
      typeof value.evidence.evidenceFingerprint !== 'string' || !Array.isArray(value.evidence.items) ||
      value.evidence.items.length > 256) return false;
  const ids = new Set();
  for (const item of value.evidence.items) {
    if (!item || typeof item.id !== 'string' || ids.has(item.id) ||
        !['STORY_BEAT', 'TRANSCRIPT', 'FRAME', 'VISUAL', 'AUDIO_SUMMARY', 'SHOT'].includes(item.type) ||
        typeof item.sourceId !== 'string' || !/^source-\d+$/.test(item.sourceId) ||
        !item.range || !Number.isSafeInteger(item.range.startMs) || !Number.isSafeInteger(item.range.endMs) ||
        item.range.startMs < 0 || item.range.endMs <= item.range.startMs ||
        typeof item.summary !== 'string' || item.summary.length > 1000) return false;
    ids.add(item.id);
  }
  const wire = JSON.stringify(value).toLowerCase();
  return !['"uri"', '"proxyuri"', '"sourcefingerprint"', '"artifactpath"', '"path"', '"bytes"', '"image"', '"audio"'].some((key) => wire.includes(key));
}

function validateProposal(value, revision, validEvidenceIds = new Set()) {
  const allowed = new Set(['remove_fumbles', 'set_audio_cleanup', 'set_audio_levels', 'add_sound_effect', 'set_background_music', 'add_captions', 'semantic_visual']);
  const stages = ['STORY', 'TAKE_SELECTION', 'CORRECTION', 'PACING', 'COVERAGE', 'OPTIONAL_TREATMENT'];
  const kinds = new Set(['REQUIRED_CLEANUP', 'OPTIONAL_STYLE', 'OPTIONAL_MUSIC']);
  const grounded = (item) => item && typeof item.rationale === 'string' && item.rationale.length > 0 &&
    Array.isArray(item.evidenceIds) && item.evidenceIds.length > 0 &&
    item.evidenceIds.every((id) => validEvidenceIds.has(id)) &&
    typeof item.confidence === 'number' && item.confidence >= 0 && item.confidence <= 1 &&
    Array.isArray(item.assumptions) && typeof item.expectedStoryEffect === 'string' && item.expectedStoryEffect.length > 0;
  if (value?.type === 'evidence_request') {
    const query = value.query;
    if (!query || typeof query.request !== 'string' || query.request.length > 2000 ||
        !Array.isArray(query.evidenceTypes) || query.evidenceTypes.length > 6 ||
        !query.evidenceTypes.every((type) => ['STORY_BEAT', 'TRANSCRIPT', 'FRAME', 'VISUAL', 'AUDIO_SUMMARY', 'SHOT'].includes(type))) {
      throw new Error('Invalid bounded evidence request.');
    }
    return value;
  }
  if (!value || value.type !== 'proposal' || value.expectedRevision !== revision || !Array.isArray(value.operations)) throw new Error('Invalid planner proposal envelope.');
  if (value.operations.some((operation) => !operation || !allowed.has(operation.type) || typeof operation.id !== 'string' || typeof operation.enabled !== 'boolean')) throw new Error('Planner returned an unsupported operation.');
  if (value.operations.some((operation) => !validSemanticOperation(operation, validEvidenceIds))) throw new Error('Planner returned an unsafe or ungrounded semantic visual.');
  const semantics = value.operations.filter((operation) => operation.enabled && operation.type === 'semantic_visual').sort((a, b) => a.range.startMs - b.range.startMs);
  if (semantics.some((operation, index) =>
    semantics.filter((candidate) => candidate.range.startMs < operation.range.endMs && operation.range.startMs < candidate.range.endMs).length > 2 ||
    semantics.slice(index).filter((candidate) => candidate.range.startMs < operation.range.startMs + 10000).length > 3)) {
    throw new Error('Planner exceeded semantic visual density.');
  }
  if (!Array.isArray(value.evidenceIds) || !value.evidenceIds.length || value.evidenceIds.some((id) => !validEvidenceIds.has(id))) {
    throw new Error('Planner returned missing, stale, or invented evidence.');
  }
  const plan = value.plan;
  if (!plan || !Array.isArray(plan.story) || !plan.story.length || !plan.story.every((choice) =>
    grounded(choice) && typeof choice.beatId === 'string' && choice.beatId.length > 0)) {
    throw new Error('Planner returned an invalid story and take plan.');
  }
  if (!Array.isArray(plan.decisions) || !plan.decisions.length || !plan.decisions.every((decision) =>
    grounded(decision) && typeof decision.id === 'string' && stages.includes(decision.stage) && kinds.has(decision.kind))) {
    throw new Error('Planner returned invalid staged editorial decisions.');
  }
  const decisionIds = new Set(plan.decisions.map((decision) => decision.id));
  if (decisionIds.size !== plan.decisions.length) throw new Error('Planner returned duplicate editorial decisions.');
  let previousStage = -1;
  for (const decision of plan.decisions) {
    const stage = stages.indexOf(decision.stage);
    if (stage < previousStage) throw new Error('Editorial decisions are not in staged order.');
    previousStage = stage;
    if (decision.stage !== 'OPTIONAL_TREATMENT' && decision.kind !== 'REQUIRED_CLEANUP') {
      throw new Error('Required cleanup must be separate from optional style and music.');
    }
    if (decision.stage === 'OPTIONAL_TREATMENT' && decision.kind === 'REQUIRED_CLEANUP') {
      throw new Error('Optional treatment cannot be labeled required cleanup.');
    }
  }
  const operationIds = new Set(value.operations.map((operation) => operation.id));
  if (!Array.isArray(plan.operationPlans) || plan.operationPlans.length !== operationIds.size ||
      !plan.operationPlans.every((item) => grounded(item) && operationIds.has(item.operationId) && decisionIds.has(item.decisionId)) ||
      new Set(plan.operationPlans.map((item) => item.operationId)).size !== operationIds.size) {
    throw new Error('Every operation must have one grounded editorial plan.');
  }
  const decisionById = new Map(plan.decisions.map((decision) => [decision.id, decision]));
  for (const operation of value.operations) {
    const explanation = plan.operationPlans.find((item) => item.operationId === operation.id);
    const decision = decisionById.get(explanation.decisionId);
    if (['add_sound_effect', 'set_background_music'].includes(operation.type) && decision.kind === 'REQUIRED_CLEANUP') {
      throw new Error('Music and sound effects must be optional treatment.');
    }
  }
  return value;
}

async function plan(payload) {
  if (!process.env.MOONSHOT_API_KEY) throw Object.assign(new Error('MOONSHOT_API_KEY is not configured'), { status: 503 });
  if (!validateRequest(payload)) throw Object.assign(new Error('Invalid planner request'), { status: 400 });
  const client = new OpenAI({ apiKey: process.env.MOONSHOT_API_KEY, baseURL: process.env.MOONSHOT_BASE_URL || 'https://api.moonshot.ai/v1' });
  const completion = await client.chat.completions.create({
    model: process.env.MOONSHOT_MODEL || 'kimi-k3', response_format: { type: 'json_object' }, max_completion_tokens: 4096,
    messages: [
      { role: 'system', content: `${CREATOR_PREFERENCE_POLICY}\n${SYSTEM_POLICY}` },
      { role: 'user', content: JSON.stringify(payload) },
    ],
  });
  return validateProposal(JSON.parse(completion.choices?.[0]?.message?.content), payload.projectRevision, new Set(payload.evidence.items.map((item) => item.id)));
}

module.exports = { plan, validateProposal, validateRequest, SYSTEM_POLICY };
