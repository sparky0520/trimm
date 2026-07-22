'use strict';

require('dotenv').config({ path: require('node:path').join(__dirname, '..', '..', '..', '.env.local') });
require('dotenv').config({ path: require('node:path').join(__dirname, '..', '..', '..', '.env') });
const http = require('node:http');
const OpenAI = require('openai').default;
const transcriptionCore = require('./transcription-core.cjs');
const { TranscriptionUploads } = require('./transcription-uploads.cjs');
const { fromEnvironment: authenticationFromEnvironment } = require('./authentication.cjs');
const desktopPlanner = require('./desktop-planner-core.cjs');

const port = Number(process.env.PORT || 8787);
const apiKey = process.env.MOONSHOT_API_KEY;
const baseURL = process.env.MOONSHOT_BASE_URL || 'https://api.moonshot.ai/v1';
const model = process.env.MOONSHOT_MODEL || 'kimi-k3';
const MAX_BODY_BYTES = 512 * 1024;
const transcriptionUploads = new TranscriptionUploads();
const authentication = authenticationFromEnvironment();

function json(response, status, body) {
  const encoded = Buffer.from(JSON.stringify(body));
  response.writeHead(status, { 'content-type': 'application/json', 'content-length': encoded.length, 'cache-control': 'no-store' });
  response.end(encoded);
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
  const requestUrl = new URL(request.url, 'http://localhost');
  if (request.method === 'GET' && requestUrl.pathname === '/health') return json(response, 200, { ok: true, model, authentication: authentication.configured() });
  let principal;
  if (requestUrl.pathname.startsWith('/v1/')) {
    try { principal = await authentication.authenticate(request); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 401, { error: error?.message || 'Unauthorized', code: error?.code || 'unauthorized' }); }
  }
  const uploadMatch = requestUrl.pathname.match(/^\/v1\/transcribe\/uploads\/([0-9a-f-]{36})(\/complete)?$/i);
  if (request.method === 'POST' && requestUrl.pathname === '/v1/transcribe/uploads') {
    try {
      transcriptionUploads.cleanup();
      const upload = transcriptionUploads.create({ ...(await collect(request, 4096)), owner: principal.id });
      return json(response, 201, { id: upload.id, offset: upload.offset, chunkSize: 4 * 1024 * 1024 });
    } catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 400, { error: error?.message || 'Could not create upload' }); }
  }
  if (uploadMatch && request.method === 'HEAD' && !uploadMatch[2]) {
    try { const upload = transcriptionUploads.read(uploadMatch[1], principal.id); response.writeHead(204, { 'upload-offset': upload.offset, 'cache-control': 'no-store' }); return response.end(); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 404, { error: error?.message }); }
  }
  if (uploadMatch && request.method === 'PUT' && !uploadMatch[2]) {
    try { const upload = await transcriptionUploads.append(uploadMatch[1], principal.id, request.headers['upload-offset'], request); return json(response, 200, { offset: upload.offset }); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 500, { error: error?.message, offset: error?.offset }); }
  }
  if (uploadMatch && request.method === 'DELETE' && !uploadMatch[2]) {
    try { transcriptionUploads.read(uploadMatch[1], principal.id); transcriptionUploads.remove(uploadMatch[1]); response.writeHead(204); return response.end(); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 404, { error: error?.message }); }
  }
  if (uploadMatch && request.method === 'POST' && uploadMatch[2]) {
    try { const upload = transcriptionUploads.complete(uploadMatch[1], principal.id); const result = await transcriptionCore.transcribe(upload.stream); transcriptionUploads.remove(uploadMatch[1]); return json(response, 200, result); }
    catch (error) { console.error('[transcribe-upload] request failed', { message: error?.message, status: error?.status }); return json(response, Number.isInteger(error?.status) ? error.status : 502, { error: error?.message || 'Transcription failed', offset: error?.offset }); }
  }
  if (request.method === 'POST' && request.url === '/v1/transcribe') {
    try {
      transcriptionCore.validateUpload(request.headers);
      return json(response, 200, await transcriptionCore.transcribe(request));
    } catch (error) {
      console.error('[transcribe] request failed', { message: error?.message, status: error?.status });
      return json(response, error?.status && Number.isInteger(error.status) ? error.status : 502, { error: error?.message || 'Transcription failed' });
    }
  }
  if (request.method === 'POST' && request.url === '/v1/desktop/plan') {
    try {
      const result = await planDesktop(await collect(request, 7 * 1024 * 1024));
      return json(response, result.ok ? 200 : result.error?.category === 'authentication' ? 503 : 502, result);
    } catch (error) {
      console.error('[desktop-planner] request failed', { message: error?.message, status: error?.status });
      return json(response, Number.isInteger(error?.status) ? error.status : 502, { error: error?.message || 'Planner failed' });
    }
  }
  if (request.method !== 'POST' || request.url !== '/v1/mobile/plan') return json(response, 404, { error: 'Not found' });
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
