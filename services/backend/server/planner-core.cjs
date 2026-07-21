'use strict';

const crypto = require('node:crypto');
const OpenAI = require('openai').default;

function authorized(header, expectedToken = process.env.CTRLB_PLANNER_CLIENT_TOKEN) {
  if (!expectedToken) return false;
  const supplied = String(header || '').replace(/^Bearer\s+/i, '');
  const expected = Buffer.from(expectedToken); const actual = Buffer.from(supplied);
  return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

function validateRequest(value) {
  if (!value || typeof value !== 'object' || typeof value.prompt !== 'string' || value.prompt.length > 2000) return false;
  if (!Array.isArray(value.transcript) || value.transcript.length > 320 || !Array.isArray(value.operations)) return false;
  const wire = JSON.stringify(value).toLowerCase();
  return !['"uri"', '"proxyuri"', '"fingerprint"', '"path"', '"bytes"', '"image"', '"audio"'].some((key) => wire.includes(key));
}

function validateProposal(value, revision) {
  const allowed = new Set(['remove_fumbles', 'set_color_grade', 'set_audio_cleanup', 'set_audio_levels', 'add_transition', 'add_motion_preset', 'add_sound_effect', 'set_background_music', 'add_captions']);
  if (!value || value.type !== 'proposal' || value.expectedRevision !== revision || !Array.isArray(value.operations)) throw new Error('Invalid planner proposal envelope.');
  if (value.operations.some((operation) => !operation || !allowed.has(operation.type) || typeof operation.id !== 'string' || typeof operation.enabled !== 'boolean')) throw new Error('Planner returned an unsupported operation.');
  return value;
}

async function plan(payload) {
  if (!process.env.MOONSHOT_API_KEY) throw Object.assign(new Error('MOONSHOT_API_KEY is not configured'), { status: 503 });
  if (!validateRequest(payload)) throw Object.assign(new Error('Invalid planner request'), { status: 400 });
  const client = new OpenAI({ apiKey: process.env.MOONSHOT_API_KEY, baseURL: process.env.MOONSHOT_BASE_URL || 'https://api.moonshot.ai/v1' });
  const completion = await client.chat.completions.create({
    model: process.env.MOONSHOT_MODEL || 'kimi-k3', response_format: { type: 'json_object' }, max_completion_tokens: 4096,
    messages: [
      { role: 'system', content: `You are ctrlB's conservative mobile video-edit planner. Return one JSON object only. Envelope: {"type":"proposal","expectedRevision":number,"summary":string,"operations":array}. Preserve useful existing operations. Every operation requires id, enabled, and one of these type values: remove_fumbles, set_color_grade, set_audio_cleanup, set_audio_levels, add_transition, add_motion_preset, add_sound_effect, set_background_music, add_captions. Never invent timestamps outside media.durationMs. Only use catalog IDs already present in existing operations. Prefer transcript-grounded edits and make no destructive low-confidence cut.` },
      { role: 'user', content: JSON.stringify(payload) },
    ],
  });
  return validateProposal(JSON.parse(completion.choices?.[0]?.message?.content), payload.projectRevision);
}

module.exports = { authorized, plan, validateProposal, validateRequest };
