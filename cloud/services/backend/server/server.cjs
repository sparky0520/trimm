'use strict';

// Production HTTP entrypoint for the shared trimm backend.

require('dotenv').config({ path: require('node:path').join(__dirname, '..', '..', '..', '.env.local') });
require('dotenv').config({ path: require('node:path').join(__dirname, '..', '..', '..', '.env') });
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const OpenAI = require('openai').default;
const transcriptionCore = require('./transcription-core.cjs');
const { TranscriptionUploads } = require('./transcription-uploads.cjs');
const { fromEnvironment: authenticationFromEnvironment } = require('./authentication.cjs');
const { IdentityRateLimiter, auditEvent, correlationId, requireScope } = require('./authorization.cjs');
const { DerivativeJobs } = require('./derivative-jobs.cjs');
const { PostgresDerivativeJobs } = require('./postgres-derivative-jobs.cjs');
const { Layer2Orchestrator } = require('./layer2-orchestrator.cjs');
const { AgentRuns } = require('./agent-runs.cjs');
const { Conversations } = require('./conversations.cjs');
const { PostgresAgentRunStore, VolatileAgentRunStore } = require('./agent-run-store.cjs');
const { createGeminiDecisionProvider } = require('./gemini-agent.cjs');
const { AudioGeneration } = require('./audio-generation.cjs');

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
const rateLimiter = new IdentityRateLimiter({ limit: Number(process.env.TRIMM_RATE_LIMIT_PER_MINUTE) || 120 });
if (process.env.NODE_ENV === 'production' && !process.env.SUPABASE_DB_URL) {
  throw new Error('Production database is not configured; agent run persistence requires it.');
}
const derivativeJobs = process.env.SUPABASE_DB_URL
  ? new PostgresDerivativeJobs({ databaseUrl: process.env.SUPABASE_DB_URL })
  : new DerivativeJobs({});
const layer2Orchestrator = new Layer2Orchestrator({
  derivativeJobs,
  decide: createGeminiDecisionProvider(),
});
const agentRuns = new AgentRuns({
  orchestrator: layer2Orchestrator,
  store: process.env.SUPABASE_DB_URL
    ? new PostgresAgentRunStore({ databaseUrl: process.env.SUPABASE_DB_URL })
    : new VolatileAgentRunStore(),
});
const conversations = new Conversations({ agentRuns });
const audioGeneration = new AudioGeneration();
const derivativeCleanupTimer = setInterval(() => {
  Promise.resolve(derivativeJobs.cleanup()).catch((error) => {
    console.error(JSON.stringify({ event: 'derivative_cleanup_failed', message: error.message }));
  });
}, 15 * 60 * 1000);
derivativeCleanupTimer.unref();
authentication.assertProductionConfigured();

function policyFor(request, pathname) {
  if (pathname.startsWith('/v1/derivatives/')) return {
    scope: 'derivatives:create',
    action: pathname === '/v1/derivatives/activity' ? 'read' : request.method === 'DELETE' ? 'cancel' : request.method === 'PUT' ? 'upload' : 'authorize',
    resourceType: 'derivative_job',
  };
  if (pathname.startsWith('/v1/transcribe')) return { scope: 'transcribe:create', action: request.method === 'DELETE' ? 'cancel' : request.method === 'PUT' ? 'update' : 'create', resourceType: 'transcription' };
  if (pathname === '/v1/agent/runs' || pathname.startsWith('/v1/conversations') ||
      pathname.startsWith('/v1/audio') ||
      /^\/v1\/agent\/runs\/[0-9a-f-]{36}(?:\/continue)?$/i.test(pathname)) {
    return { scope: 'plan:create', action: 'create', resourceType: 'plan' };
  }
  return null;
}

function audit(entry) { console.info('[audit]', auditEvent(entry)); }

function json(response, status, body, headers = {}) {
  const encoded = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {
    'content-type': 'application/json',
    'content-length': encoded.length,
    'cache-control': 'no-store',
    ...headers,
  });
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
    maximumCount: job.maximumCount, status: job.status, offset: job.offset, createdAt: new Date(job.createdAt).toISOString(),
    expiresAt: new Date(job.expiresAt).toISOString(),
    mediaMode: job.mediaMode, selectionReason: job.selectionReason,
    timelineReferences: job.timelineReferences, consent: job.consent,
    providerDisclosures: job.providerDisclosures, resolution: job.resolution,
    retentionUntil: new Date(job.deleteAt).toISOString(),
    deletedAt: job.deletedAt ? new Date(job.deletedAt).toISOString() : null,
  };
}

function derivativeContext(request) {
  return {
    projectId: String(request.headers['x-trimm-project-id'] || ''),
    deviceSessionId: String(request.headers['x-trimm-device-session-id'] || ''),
  };
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



const server = http.createServer(async (request, response) => {
  const requestUrl = new URL(request.url, 'http://localhost');
  if (request.method === 'GET' && requestUrl.pathname === '/health') return json(response, 200, { ok: true, model, authentication: authentication.configured() });
  if (request.method === 'GET' && (requestUrl.pathname === '/privacy' || requestUrl.pathname === '/privacy/')) {
    return html(response, 200, privacyPolicy);
  }
  let principal;
  const requestId = correlationId(request);
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

  const derivativeMatch = requestUrl.pathname.match(/^\/v1\/derivatives\/jobs\/([0-9a-f-]{36})(\/complete)?$/i);
  if (request.method === 'POST' && requestUrl.pathname === '/v1/derivatives/jobs') {
    try {
      await derivativeJobs.cleanup();
      const authorized = await derivativeJobs.authorize(principal, await collect(request, 16 * 1024));
      return json(response, authorized.reused ? 200 : 201, { ...derivativeView(authorized.job), reused: authorized.reused, chunkSize: 4 * 1024 * 1024, requestId });
    } catch (error) {
      return json(response, Number.isInteger(error?.status) ? error.status : 400, { error: error?.message || 'Could not authorize derivative.', code: error?.code, requestId });
    }
  }
  if (request.method === 'GET' && requestUrl.pathname === '/v1/derivatives/activity') {
    return json(response, 200, { activity: await derivativeJobs.listActivity(principal, requestUrl.searchParams.get('projectId')), requestId });
  }
  if (derivativeMatch && request.method === 'HEAD' && !derivativeMatch[2]) {
    try {
      const job = await derivativeJobs.read(derivativeMatch[1], principal, derivativeContext(request));
      const fileExists = fs.existsSync(derivativeJobs.mediaPath ? derivativeJobs.mediaPath(job.id) : '');
      const isExpired = job.expiresAt <= Date.now() || (job.deleteAt && job.deleteAt <= Date.now());
      const offset = (!fileExists || isExpired) ? 0 : job.offset;
      response.writeHead(204, { 'upload-offset': offset, 'upload-expires': new Date(job.expiresAt).toISOString(), 'cache-control': 'no-store' });
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
    try { await derivativeJobs.delete(derivativeMatch[1], principal, derivativeContext(request)); response.writeHead(204); return response.end(); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 404, { error: error?.message, code: error?.code, requestId }); }
  }
  if (derivativeMatch && request.method === 'POST' && derivativeMatch[2]) {
    try {
      const completed = await derivativeJobs.complete(derivativeMatch[1], principal, await collect(request, 4096), derivativeContext(request));
      return json(response, 200, {
        ...derivativeView(completed.job), reused: completed.reused,
        requestId,
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

  if (request.method === 'POST' && requestUrl.pathname === '/v1/audio/generate-bgm') {
    try {
      const { prompt, durationSecs, beats } = await collect(request);
      if (!prompt || !durationSecs) throw Object.assign(new Error('Missing prompt or durationSecs'), { status: 400 });
      return json(response, 200, await audioGeneration.generateBgm(prompt, durationSecs, beats));
    } catch (error) {
      console.error('[audio-generate-bgm] request failed', { message: error?.message });
      return json(response, Number.isInteger(error?.status) ? error.status : 500, { error: error?.message || 'BGM generation failed' });
    }
  }

  if (request.method === 'POST' && requestUrl.pathname === '/v1/audio/fetch-sfx') {
    try {
      const { prompt } = await collect(request);
      if (!prompt) throw Object.assign(new Error('Missing prompt'), { status: 400 });
      return json(response, 200, await audioGeneration.fetchSfx(prompt));
    } catch (error) {
      console.error('[audio-fetch-sfx] request failed', { message: error?.message });
      return json(response, Number.isInteger(error?.status) ? error.status : 500, { error: error?.message || 'SFX fetch failed' });
    }
  }

  if (request.method === 'POST' && requestUrl.pathname === '/v1/agent/runs') {
    try {
      return json(response, 201, await agentRuns.start(principal, await collect(request)));
    } catch (error) {
      console.error('[agent-run] request failed', { message: error?.message, status: error?.status, code: error?.code });
      return json(response, Number.isInteger(error?.status) ? error.status : 502, {
        error: error?.message || 'Agent run failed',
        code: error?.code || 'agent_run_failed',
        ...(error?.run ? { run: error.run } : {}),
        requestId,
      });
    }
  }
  if (request.method === 'POST' && requestUrl.pathname === '/v1/conversations') {
    try { return json(response, 201, await conversations.create(principal, await collect(request))); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 502, { error: error?.message || 'Conversation failed', code: error?.code || 'conversation_failed', requestId }); }
  }
  const conversationMessageMatch = requestUrl.pathname.match(/^\/v1\/conversations\/([0-9a-f-]{36})\/messages$/i);
  if (request.method === 'POST' && conversationMessageMatch) {
    try { return json(response, 200, await conversations.message(principal, conversationMessageMatch[1], await collect(request, 64 * 1024))); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 502, { error: error?.message || 'Conversation could not continue', code: error?.code || 'conversation_failed', requestId }); }
  }
  const proposalReviewMatch = requestUrl.pathname.match(/^\/v1\/conversations\/([0-9a-f-]{36})\/proposals\/([^/]+)\/(accept|reject)$/i);
  if (request.method === 'POST' && proposalReviewMatch) {
    try { return json(response, 200, await conversations.review(principal, proposalReviewMatch[1], proposalReviewMatch[2], proposalReviewMatch[3], await collect(request, 64 * 1024))); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 502, { error: error?.message || 'Proposal review failed', code: error?.code || 'proposal_review_failed', requestId }); }
  }
  const conversationMatch = requestUrl.pathname.match(/^\/v1\/conversations\/([0-9a-f-]{36})$/i);
  if (request.method === 'GET' && conversationMatch) {
    try { return json(response, 200, await conversations.read(principal, conversationMatch[1])); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 404, { error: error?.message || 'Conversation not found', code: error?.code || 'conversation_not_found', requestId }); }
  }
  if (request.method === 'DELETE' && conversationMatch) {
    try { return json(response, 200, await conversations.cancel(principal, conversationMatch[1])); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 404, { error: error?.message || 'Conversation not found', code: error?.code || 'conversation_not_found', requestId }); }
  }
  const agentRunContinueMatch = requestUrl.pathname.match(/^\/v1\/agent\/runs\/([0-9a-f-]{36})\/continue$/i);
  if (request.method === 'POST' && agentRunContinueMatch) {
    try {
      return json(response, 200, await agentRuns.continue(principal, agentRunContinueMatch[1], await collect(request, 16 * 1024)));
    } catch (error) {
      return json(response, Number.isInteger(error?.status) ? error.status : 502, {
        error: error?.message || 'Agent run could not continue',
        code: error?.code || 'agent_run_failed',
        ...(error?.run ? { run: error.run } : {}),
        requestId,
      });
    }
  }
  const agentRunMatch = requestUrl.pathname.match(/^\/v1\/agent\/runs\/([0-9a-f-]{36})$/i);
  if (request.method === 'DELETE' && agentRunMatch) {
    try { return json(response, 200, await agentRuns.cancel(principal, agentRunMatch[1])); }
    catch (error) { return json(response, Number.isInteger(error?.status) ? error.status : 404, { error: error?.message, code: error?.code, requestId }); }
  }
  if (request.method === 'GET' && agentRunMatch) {
    try { return json(response, 200, await agentRuns.read(principal, agentRunMatch[1])); }
    catch (error) {
      return json(response, Number.isInteger(error?.status) ? error.status : 404, {
        error: error?.message || 'Agent run not found', code: error?.code || 'agent_run_not_found', requestId,
      });
    }
  }
  return json(response, 404, { error: 'Not found' });
});

// A transcription request includes both a potentially slow mobile upload and
// the provider wait. Keep it streaming instead of terminating it at Node's
// default whole-request timeout.
server.requestTimeout = 0;
server.keepAliveTimeout = 65_000;
server.listen(port, () => console.log(`[trimm-backend] listening on :${port} with ${model}`));
