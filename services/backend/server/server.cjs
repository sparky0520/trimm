'use strict';

// Production HTTP entrypoint for the shared ctrlB backend.

require('dotenv').config({ path: require('node:path').join(__dirname, '..', '..', '..', '.env.local') });
require('dotenv').config({ path: require('node:path').join(__dirname, '..', '..', '..', '.env') });
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const OpenAI = require('openai').default;
const transcriptionCore = require('./transcription-core.cjs');
const { TranscriptionUploads } = require('./transcription-uploads.cjs');
const { fromEnvironment: authenticationFromEnvironment } = require('./authentication.cjs');
const desktopPlanner = require('./desktop-planner-core.cjs');
const { IdentityRateLimiter, auditEvent, correlationId, requireScope } = require('./authorization.cjs');
const { BillingService, VolatileBillingStore } = require('./billing-core.cjs');
const { PostgresBillingStore } = require('./postgres-billing-store.cjs');
const { createPaddleWebhookVerifier } = require('./paddle-billing.cjs');
const { GooglePlayPurchaseVerifier } = require('./google-play-billing.cjs');
const { DerivativeJobs } = require('./derivative-jobs.cjs');
const { Layer2Orchestrator } = require('./layer2-orchestrator.cjs');
const plannerCore = require('./planner-core.cjs');

const port = Number(process.env.PORT || 8787);
const apiKey = process.env.MOONSHOT_API_KEY;
const baseURL = process.env.MOONSHOT_BASE_URL || 'https://api.moonshot.ai/v1';
const model = process.env.MOONSHOT_MODEL || 'kimi-k3';
const MAX_BODY_BYTES = 512 * 1024;
const privacyPolicy = fs.readFileSync(path.join(__dirname, 'privacy.html'));
const transcriptionUploads = new TranscriptionUploads();
const uploadCleanupTimer = setInterval(() => transcriptionUploads.cleanup(), 15 * 60 * 1000);
uploadCleanupTimer.unref();
const authentication = authenticationFromEnvironment();
const rateLimiter = new IdentityRateLimiter({ limit: Number(process.env.CTRLB_RATE_LIMIT_PER_MINUTE) || 120 });
if (process.env.NODE_ENV === 'production' && !process.env.SUPABASE_DB_URL) {
  throw new Error('Production billing database is not configured.');
}
const billing = new BillingService({
  store: process.env.SUPABASE_DB_URL
    ? new PostgresBillingStore({ databaseUrl: process.env.SUPABASE_DB_URL })
    : new VolatileBillingStore(),
  verifiers: {
    paddle: createPaddleWebhookVerifier({ secret: process.env.CTRLB_PADDLE_WEBHOOK_SECRET }),
  },
});
const derivativeJobs = new DerivativeJobs({ accessFor: (userId) => billing.accessFor(userId) });
const layer2Orchestrator = new Layer2Orchestrator({
  derivativeJobs,
  decide: async ({ systemPolicy, inventory, evidence }) => {
    if (!apiKey) throw Object.assign(new Error('MOONSHOT_API_KEY is not configured'), { status: 503 });
    const client = new OpenAI({ apiKey, baseURL });
    const completion = await client.chat.completions.create({
      model,
      response_format: { type: 'json_object' },
      max_completion_tokens: 4096,
      messages: [
        { role: 'system', content: systemPolicy },
        { role: 'user', content: JSON.stringify({ inventory, evidence }) },
      ],
    });
    return JSON.parse(completion.choices?.[0]?.message?.content);
  },
});
const derivativeCleanupTimer = setInterval(() => derivativeJobs.cleanup(), 15 * 60 * 1000);
derivativeCleanupTimer.unref();
const googlePlay = process.env.CTRLB_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
  ? new GooglePlayPurchaseVerifier()
  : null;
authentication.assertProductionConfigured();

function policyFor(request, pathname) {
  if (pathname.startsWith('/v1/derivatives/')) return {
    scope: 'derivatives:create',
    action: pathname === '/v1/derivatives/activity' ? 'read' : request.method === 'DELETE' ? 'cancel' : request.method === 'PUT' ? 'upload' : 'authorize',
    resourceType: 'derivative_job',
  };
  if (pathname.startsWith('/v1/transcribe')) return { scope: 'transcribe:create', action: request.method === 'DELETE' ? 'cancel' : request.method === 'PUT' ? 'update' : 'create', resourceType: 'transcription' };
  if (pathname === '/v1/desktop/plan' || pathname === '/v1/mobile/plan' || pathname === '/v1/mobile/orchestrate') {
    return { scope: 'plan:create', action: 'create', resourceType: 'plan' };
  }
  if (pathname === '/v1/billing/catalog' || pathname === '/v1/billing/status') return { action: 'read', resourceType: 'billing' };
  if (pathname === '/v1/billing/google-play/verify') return { action: 'verify', resourceType: 'billing' };
  return null;
}

function audit(entry) { console.info('[audit]', auditEvent(entry)); }

function json(response, status, body) {
  const encoded = Buffer.from(JSON.stringify(body));
  response.writeHead(status, { 'content-type': 'application/json', 'content-length': encoded.length, 'cache-control': 'no-store' });
  response.end(encoded);
}

function html(response, status, body) {
  response.writeHead(status, {
    'content-type': 'text/html; charset=utf-8',
    'content-length': body.length,
    'cache-control': 'public, max-age=3600',
    'x-content-type-options': 'nosniff',
  });
  response.end(body);
}

function derivativeView(job) {
  return {
    id: job.id, projectId: job.projectId, deviceSessionId: job.deviceSessionId,
    purpose: job.purpose, type: job.type, acceptedFormat: job.acceptedFormat,
    maximumBytes: job.maximumBytes, maximumDurationMs: job.maximumDurationMs,
    maximumCount: job.maximumCount, offset: job.offset, status: job.status,
    expiresAt: new Date(job.expiresAt).toISOString(), contentKey: job.contentKey,
  };
}

function derivativeContext(request) {
  return {
    projectId: String(request.headers['x-ctrlb-project-id'] || ''),
    deviceSessionId: String(request.headers['x-ctrlb-device-session-id'] || ''),
  };
}

function validateRequest(value) {
  if (!value || typeof value !== 'object' || typeof value.prompt !== 'string' || value.prompt.length > 2000) return false;
  if (!Array.isArray(value.operations) || !value.evidence || value.evidence.schemaVersion !== 1 ||
      typeof value.evidence.evidenceFingerprint !== 'string' || !Array.isArray(value.evidence.items) || value.evidence.items.length > 256) return false;
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
  // Shared mobile policy: Color requires frame evidence. Legacy generic transitions and motion presets are unavailable.
  void allowed;
  return plannerCore.validateProposal(value, revision, validEvidenceIds);
}

async function collect(request, maxBytes = MAX_BODY_BYTES) {
  const chunks = []; let size = 0;
  for await (const chunk of request) { size += chunk.length; if (size > maxBytes) throw Object.assign(new Error('Request too large'), { status: 413 }); chunks.push(chunk); }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

async function collectBuffer(request, maxBytes = 256 * 1024) {
  const chunks = []; let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > maxBytes) throw Object.assign(new Error('Request too large'), { status: 413 });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
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
  if (request.method === 'GET' && (requestUrl.pathname === '/privacy' || requestUrl.pathname === '/privacy/')) {
    return html(response, 200, privacyPolicy);
  }
  let principal;
  const requestId = correlationId(request);
  if (request.method === 'POST' && requestUrl.pathname === '/v1/billing/webhook') {
    try {
      rateLimiter.consume(request, null);
      const provider = requestUrl.searchParams.get('provider') || '';
      const result = await billing.ingest(provider, {
        body: await collectBuffer(request),
        signature: provider === 'paddle'
          ? request.headers['paddle-signature']
          : request.headers['x-ctrlb-signature'],
      });
      audit({ action: 'billing.webhook', resourceType: 'billing', outcome: 'allowed', requestId });
      return json(response, 200, { accepted: true, duplicate: result.duplicate, requestId });
    } catch (error) {
      audit({ action: 'billing.webhook', resourceType: 'billing', outcome: 'denied', reason: error?.code, requestId });
      return json(response, Number.isInteger(error?.status) ? error.status : 503, {
        error: error?.message || 'Billing webhook failed.', code: error?.code || 'billing_webhook_failed', requestId,
      });
    }
  }
  if (requestUrl.pathname.startsWith('/v1/')) {
    const policy = policyFor(request, requestUrl.pathname);
    try {
      principal = await authentication.authenticate(request);
      if (policy?.scope) requireScope(principal, policy.scope);
      rateLimiter.consume(request, principal);
      if (policy) audit({ principal, ...policy, outcome: 'allowed', requestId });
    } catch (error) {
      if (!principal) {
        try { rateLimiter.consume(request, null); }
        catch (rateError) { error = rateError; }
      }
      if (policy) audit({ principal, ...policy, outcome: 'denied', reason: error?.code, requestId });
      return json(response, Number.isInteger(error?.status) ? error.status : 401, { error: error?.message || 'Unauthorized', code: error?.code || 'unauthorized', requestId });
    }
  }
  if (request.method === 'GET' && requestUrl.pathname === '/v1/billing/catalog') {
    return json(response, 200, billing.catalog());
  }
  if (request.method === 'GET' && requestUrl.pathname === '/v1/billing/status') {
    try { return json(response, 200, await billing.accessFor(principal.id)); }
    catch (error) {
      return json(response, Number.isInteger(error?.status) ? error.status : 503, {
        error: error?.message || 'Billing status failed.', code: error?.code || 'billing_status_failed', requestId,
      });
    }
  }
  if (request.method === 'POST' && requestUrl.pathname === '/v1/billing/google-play/verify') {
    try {
      if (!googlePlay) throw Object.assign(new Error('Google Play purchase verification is not configured.'), {
        status: 503, code: 'billing_unconfigured',
      });
      const submission = await collect(request, 8 * 1024);
      const verified = await googlePlay.verify({
        purchaseToken: submission.purchaseToken,
        productId: submission.productId,
        canonicalUserId: principal.id,
      });
      const [result] = await billing.reconcile('google_play', [verified]);
      if (verified.acknowledgementPending) {
        await googlePlay.acknowledge({
          purchaseToken: submission.purchaseToken,
          productId: submission.productId,
        });
      }
      audit({ principal, action: 'billing.verify', resourceType: 'billing', outcome: 'allowed', requestId });
      return json(response, 200, {
        accepted: true,
        duplicate: result.duplicate,
        access: await billing.accessFor(principal.id),
        requestId,
      });
    } catch (error) {
      audit({ principal, action: 'billing.verify', resourceType: 'billing', outcome: 'denied', reason: error?.code, requestId });
      return json(response, Number.isInteger(error?.status) ? error.status : 503, {
        error: error?.message || 'Purchase verification failed.',
        code: error?.code || 'purchase_verification_failed',
        requestId,
      });
    }
  }
  const derivativeMatch = requestUrl.pathname.match(/^\/v1\/derivatives\/jobs\/([0-9a-f-]{36})(\/complete)?$/i);
  if (request.method === 'POST' && requestUrl.pathname === '/v1/derivatives/jobs') {
    try {
      derivativeJobs.cleanup();
      const authorized = await derivativeJobs.authorize(principal, await collect(request, 16 * 1024));
      return json(response, authorized.reused ? 200 : 201, { ...derivativeView(authorized.job), reused: authorized.reused, chunkSize: 4 * 1024 * 1024, requestId });
    } catch (error) {
      return json(response, Number.isInteger(error?.status) ? error.status : 400, { error: error?.message || 'Could not authorize derivative.', code: error?.code, requestId });
    }
  }
  if (request.method === 'GET' && requestUrl.pathname === '/v1/derivatives/activity') {
    return json(response, 200, { activity: derivativeJobs.listActivity(principal, requestUrl.searchParams.get('projectId')), requestId });
  }
  if (derivativeMatch && request.method === 'HEAD' && !derivativeMatch[2]) {
    try {
      const job = derivativeJobs.read(derivativeMatch[1], principal, derivativeContext(request));
      response.writeHead(204, { 'upload-offset': job.offset, 'upload-expires': new Date(job.expiresAt).toISOString(), 'cache-control': 'no-store' });
      return response.end();
    } catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 404, { error: error?.message, code: error?.code, requestId }); }
  }
  if (derivativeMatch && request.method === 'PUT' && !derivativeMatch[2]) {
    try {
      const job = await derivativeJobs.append(derivativeMatch[1], principal, request.headers['upload-offset'], request, derivativeContext(request));
      return json(response, 200, { offset: job.offset, requestId });
    } catch (error) {
      return json(response, Number.isInteger(error?.status) ? error.status : 500, { error: error?.message, code: error?.code, offset: error?.offset, requestId });
    }
  }
  if (derivativeMatch && request.method === 'DELETE' && !derivativeMatch[2]) {
    try { derivativeJobs.cancel(derivativeMatch[1], principal, derivativeContext(request)); response.writeHead(204); return response.end(); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 404, { error: error?.message, code: error?.code, requestId }); }
  }
  if (derivativeMatch && request.method === 'POST' && derivativeMatch[2]) {
    try {
      const completed = derivativeJobs.complete(derivativeMatch[1], principal, await collect(request, 4096), derivativeContext(request));
      return json(response, 200, {
        ...derivativeView(completed.job), reused: completed.reused,
        ...(completed.result ? { result: completed.result } : {}), requestId,
      });
    } catch (error) {
      return json(response, Number.isInteger(error?.status) ? error.status : 400, { error: error?.message, code: error?.code, requestId });
    }
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
    try { const upload = transcriptionUploads.readForPrincipal(uploadMatch[1], principal); response.writeHead(204, { 'upload-offset': upload.offset, 'cache-control': 'no-store' }); return response.end(); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 404, { error: error?.message }); }
  }
  if (uploadMatch && request.method === 'PUT' && !uploadMatch[2]) {
    try { const upload = await transcriptionUploads.append(uploadMatch[1], principal.id, request.headers['upload-offset'], request); return json(response, 200, { offset: upload.offset }); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 500, { error: error?.message, offset: error?.offset }); }
  }
  if (uploadMatch && request.method === 'DELETE' && !uploadMatch[2]) {
    try { transcriptionUploads.readForPrincipal(uploadMatch[1], principal); transcriptionUploads.remove(uploadMatch[1]); response.writeHead(204); return response.end(); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 404, { error: error?.message }); }
  }
  if (uploadMatch && request.method === 'POST' && uploadMatch[2]) {
    try { const upload = transcriptionUploads.complete(uploadMatch[1], principal.id); const result = await transcriptionCore.transcribe(upload.stream); return json(response, 200, result); }
    catch (error) { console.error('[transcribe-upload] request failed', { message: error?.message, status: error?.status }); return json(response, Number.isInteger(error?.status) ? error.status : 502, { error: error?.message || 'Transcription failed', offset: error?.offset }); }
    finally { transcriptionUploads.remove(uploadMatch[1]); }
  }
  if (request.method === 'POST' && requestUrl.pathname === '/v1/transcribe') {
    try {
      transcriptionCore.validateUpload(request.headers);
      return json(response, 200, await transcriptionCore.transcribe(request));
    } catch (error) {
      console.error('[transcribe] request failed', { message: error?.message, status: error?.status });
      return json(response, error?.status && Number.isInteger(error.status) ? error.status : 502, { error: error?.message || 'Transcription failed' });
    }
  }
  if (request.method === 'POST' && requestUrl.pathname === '/v1/desktop/plan') {
    try {
      const result = await planDesktop(await collect(request, 7 * 1024 * 1024));
      return json(response, result.ok ? 200 : result.error?.category === 'authentication' ? 503 : 502, result);
    } catch (error) {
      console.error('[desktop-planner] request failed', { message: error?.message, status: error?.status });
      return json(response, Number.isInteger(error?.status) ? error.status : 502, { error: error?.message || 'Planner failed' });
    }
  }
  if (request.method === 'POST' && requestUrl.pathname === '/v1/mobile/orchestrate') {
    try {
      return json(response, 200, await layer2Orchestrator.orchestrate(principal, await collect(request)));
    } catch (error) {
      console.error('[layer2-orchestrator] request failed', { message: error?.message, status: error?.status, code: error?.code });
      return json(response, Number.isInteger(error?.status) ? error.status : 502, {
        error: error?.message || 'Layer 2 orchestration failed',
        code: error?.code || 'orchestration_failed',
        requestId,
      });
    }
  }
  if (request.method !== 'POST' || requestUrl.pathname !== '/v1/mobile/plan') return json(response, 404, { error: 'Not found' });
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
        { role: 'system', content: plannerCore.SYSTEM_POLICY },
        { role: 'user', content: JSON.stringify(payload) },
      ],
    });
    const content = completion.choices?.[0]?.message?.content;
    const proposal = validateProposal(JSON.parse(content), payload.projectRevision, new Set(payload.evidence.items.map((item) => item.id)));
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
