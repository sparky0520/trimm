'use strict';

require('dotenv').config({ path: require('node:path').join(__dirname, '..', '..', '..', '.env.local') });
require('dotenv').config({ path: require('node:path').join(__dirname, '..', '..', '..', '.env') });
const http = require('node:http');
const crypto = require('node:crypto');
const OpenAI = require('openai').default;
const transcriptionCore = require('./transcription-core.cjs');
const desktopPlanner = require('./desktop-planner-core.cjs');

const port = Number(process.env.PORT || 8787);
const apiKey = process.env.MOONSHOT_API_KEY;
const clientToken = process.env.CTRLB_PLANNER_CLIENT_TOKEN;
const baseURL = process.env.MOONSHOT_BASE_URL || 'https://api.moonshot.ai/v1';
const model = process.env.MOONSHOT_MODEL || 'kimi-k3';
const MAX_BODY_BYTES = 512 * 1024;

function json(response, status, body) {
  const encoded = Buffer.from(JSON.stringify(body));
  response.writeHead(status, { 'content-type': 'application/json', 'content-length': encoded.length, 'cache-control': 'no-store' });
  response.end(encoded);
}

function authorized(request) {
  if (!clientToken) return false;
  const supplied = String(request.headers.authorization || '').replace(/^Bearer\s+/i, '');
  const expected = Buffer.from(clientToken); const actual = Buffer.from(supplied);
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

async function collect(request, maxBytes = MAX_BODY_BYTES) {
  const chunks = []; let size = 0;
  for await (const chunk of request) { size += chunk.length; if (size > maxBytes) throw Object.assign(new Error('Request too large'), { status: 413 }); chunks.push(chunk); }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

async function planDesktop(payload) {
  const frames = Array.isArray(payload?.frameImages) ? payload.frameImages : [];
  if (!payload || !desktopPlanner.validateRequest(payload.request) || frames.length > 6 || frames.some((frame) =>
    !frame || typeof frame.id !== 'string' || typeof frame.data !== 'string' || frame.data.length > 1_000_000 || !/^[a-zA-Z0-9+/=]+$/.test(frame.data))) {
    throw Object.assign(new Error('Invalid desktop planner request'), { status: 400 });
  }
  const provider = String(process.env.CTRLB_DESKTOP_AI_PROVIDER || 'kimi').toLowerCase();
  if (!['kimi', 'openai'].includes(provider)) throw Object.assign(new Error('Unsupported desktop AI provider configuration'), { status: 503 });
  return desktopPlanner.createAiPlanner({
    provider,
    apiKey: provider === 'openai' ? process.env.OPENAI_API_KEY : process.env.MOONSHOT_API_KEY,
    baseURL: provider === 'kimi' ? baseURL : undefined,
    model: provider === 'openai' ? process.env.CTRLB_DESKTOP_OPENAI_MODEL : model,
    frameDataById: Object.fromEntries(frames.map((frame) => [frame.id, frame.data])),
  })(payload.request);
}

const server = http.createServer(async (request, response) => {
  if (request.method === 'GET' && request.url === '/health') return json(response, 200, { ok: true, model });
  if (request.method === 'POST' && request.url === '/v1/transcribe') {
    if (!authorized(request)) return json(response, 401, { error: 'Unauthorized' });
    try {
      transcriptionCore.validateUpload(request.headers);
      return json(response, 200, await transcriptionCore.transcribe(request));
    } catch (error) {
      console.error('[transcribe] request failed', { message: error?.message, status: error?.status });
      return json(response, error?.status && Number.isInteger(error.status) ? error.status : 502, { error: error?.message || 'Transcription failed' });
    }
  }
  if (request.method === 'POST' && request.url === '/v1/desktop/plan') {
    if (!authorized(request)) return json(response, 401, { error: 'Unauthorized' });
    try {
      const result = await planDesktop(await collect(request, 7 * 1024 * 1024));
      return json(response, result.ok ? 200 : result.error?.category === 'authentication' ? 503 : 502, result);
    } catch (error) {
      console.error('[desktop-planner] request failed', { message: error?.message, status: error?.status });
      return json(response, Number.isInteger(error?.status) ? error.status : 502, { error: error?.message || 'Planner failed' });
    }
  }
  if (request.method !== 'POST' || request.url !== '/v1/mobile/plan') return json(response, 404, { error: 'Not found' });
  if (!authorized(request)) return json(response, 401, { error: 'Unauthorized' });
  try {
    if (!apiKey) throw Object.assign(new Error('MOONSHOT_API_KEY is not configured'), { status: 503 });
    const payload = await collect(request);
    if (!validateRequest(payload)) return json(response, 400, { error: 'Invalid planner request' });
    const client = new OpenAI({ apiKey, baseURL });
    const completion = await client.chat.completions.create({
      model,
      response_format: { type: 'json_object' },
      max_completion_tokens: 4096,
      messages: [
        { role: 'system', content: `You are ctrlB's conservative mobile video-edit planner. Return one JSON object only. Envelope: {"type":"proposal","expectedRevision":number,"summary":string,"operations":array}. Preserve useful existing operations. Every operation requires id, enabled, and one of these type values: remove_fumbles, set_color_grade, set_audio_cleanup, set_audio_levels, add_transition, add_motion_preset, add_sound_effect, set_background_music, add_captions. Never invent timestamps outside media.durationMs. Only use catalog IDs already present in existing operations. Prefer transcript-grounded edits and make no destructive low-confidence cut.` },
        { role: 'user', content: JSON.stringify(payload) },
      ],
    });
    const content = completion.choices?.[0]?.message?.content;
    const proposal = validateProposal(JSON.parse(content), payload.projectRevision);
    return json(response, 200, proposal);
  } catch (error) {
    console.error('[mobile-planner] request failed', { message: error?.message, status: error?.status });
    return json(response, error?.status && Number.isInteger(error.status) ? error.status : 502, { error: error?.message || 'Planner failed' });
  }
});

// A transcription request includes both a potentially slow mobile upload and
// the provider wait. Keep it streaming instead of terminating it at Node's
// default whole-request timeout.
server.requestTimeout = 0;
server.keepAliveTimeout = 65_000;
server.listen(port, () => console.log(`[ctrlb-backend] listening on :${port} with ${model}`));
