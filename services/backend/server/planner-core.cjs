'use strict';

const OpenAI = require('openai').default;

function validateRequest(value) {
  if (!value || typeof value !== 'object' || typeof value.prompt !== 'string' || value.prompt.length > 2000) return false;
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
  const allowed = new Set(['remove_fumbles', 'set_audio_cleanup', 'set_audio_levels', 'add_sound_effect', 'set_background_music', 'add_captions']);
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
  if (!Array.isArray(value.evidenceIds) || !value.evidenceIds.length || value.evidenceIds.some((id) => !validEvidenceIds.has(id))) {
    throw new Error('Planner returned missing, stale, or invented evidence.');
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
      { role: 'system', content: `You are ctrlB's conservative mobile video-edit planner. Return one JSON object only. Either request bounded additional evidence with {"type":"evidence_request","query":{"request":string,"evidenceTypes":["STORY_BEAT"|"TRANSCRIPT"|"FRAME"|"VISUAL"|"AUDIO_SUMMARY"|"SHOT"],"sourceIds":[],"ranges":[],"excludeEvidenceIds":[],"budget":object}} or propose {"type":"proposal","expectedRevision":number,"summary":string,"operations":array,"evidenceIds":array}. Proposal evidenceIds must be IDs in evidence.items. Preserve useful existing operations. Every operation requires id, enabled, and one of: remove_fumbles, set_audio_cleanup, set_audio_levels, add_sound_effect, set_background_music, add_captions. Color requires frame evidence and remains unavailable to this planner. Transitions and motion presets are unavailable. Never invent timestamps or evidence. Prefer grounded edits and make no destructive low-confidence cut.` },
      { role: 'user', content: JSON.stringify(payload) },
    ],
  });
  return validateProposal(JSON.parse(completion.choices?.[0]?.message?.content), payload.projectRevision, new Set(payload.evidence.items.map((item) => item.id)));
}

module.exports = { plan, validateProposal, validateRequest };
