import "./env.js";
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Production HTTP entrypoint for the shared trimm backend.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import * as transcriptionCore from './services/transcription-core.js';
import { TranscriptionUploads } from './services/transcription-uploads.js';
import { fromEnvironment as authenticationFromEnvironment } from './middleware/authentication.js';
import { IdentityRateLimiter, auditEvent, correlationId, requireScope } from './middleware/authorization.js';
import { createOpenAIDecisionProvider } from './services/openai-agent.js';
import { AudioGeneration } from './services/audio-generation.js';
import { billingService, CREDIT_COSTS } from './services/billing.js';

const port = Number(process.env.PORT || 8787);
const apiKey = process.env.LLM_API_KEY;
const baseURL = process.env.LLM_OPENAI_BASE_URL || 'https://api.moonshot.ai/v1';
const model = process.env.MODEL || 'kimi-k2.7-code';
const MAX_BODY_BYTES = 512 * 1024;

const transcriptionUploads = new TranscriptionUploads();
const uploadCleanupTimer = setInterval(() => transcriptionUploads.cleanup(), 15 * 60 * 1000);
uploadCleanupTimer.unref();
const authentication = authenticationFromEnvironment();
const rateLimiter = new IdentityRateLimiter({ limit: Number(process.env.TRIMM_RATE_LIMIT_PER_MINUTE) || 120 });
const agentDecisionProvider = createOpenAIDecisionProvider();

const audioGeneration = new AudioGeneration();
authentication.assertProductionConfigured();

function policyFor(request, pathname) {
  if (pathname.startsWith('/v1/transcribe')) return { scope: 'transcribe:create', action: request.method === 'DELETE' ? 'cancel' : request.method === 'PUT' ? 'update' : 'create', resourceType: 'transcription' };
  if (pathname.startsWith('/v1/audio') || pathname === '/v1/chat/completion') {
    return { scope: 'plan:create', action: 'create', resourceType: 'plan' };
  }
  if (pathname.startsWith('/v1/billing')) {
    return { scope: 'billing:read', action: 'read', resourceType: 'billing' };
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
  if (request.method === 'GET' && requestUrl.pathname === '/health') {
    return json(response, 200, {
      ok: true,
      status: 'healthy',
      uptime_seconds: process.uptime(),
      memory_usage: process.memoryUsage(),
      configuration: {
        port: port,
        llm_model: model,
        llm_base_url: baseURL,
        authentication_enabled: authentication.configured(),
        supabase_connected: !!process.env.SUPABASE_URL,
        rate_limit_per_minute: Number(process.env.TRIMM_RATE_LIMIT_PER_MINUTE) || 120
      }
    });
  }

  let principal;
  const requestId = correlationId(request);
  const isWebhook = requestUrl.pathname === '/v1/billing/revenuecat-webhook';

  if (requestUrl.pathname.startsWith('/v1/') && !isWebhook) {
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

  if (request.method === 'POST' && requestUrl.pathname === '/v1/billing/revenuecat-webhook') {
    try {
      const payload = await collect(request, 1024 * 1024);
      const result = await billingService.handleRevenueCatWebhook(request.headers, payload);
      return json(response, 200, result);
    } catch (error) {
      console.error('[revenuecat-webhook] request failed', { message: error?.message, status: error?.status });
      return json(response, Number.isInteger(error?.status) ? error.status : 500, {
        error: error?.message || 'RevenueCat webhook failed',
      });
    }
  }

  if (request.method === 'GET' && requestUrl.pathname === '/v1/billing/credits') {
    try {
      const credits = await billingService.getCredits(principal.id);
      return json(response, 200, credits);
    } catch (error) {
      console.error('[billing-credits] request failed', { message: error?.message, status: error?.status });
      return json(response, Number.isInteger(error?.status) ? error.status : 500, {
        error: error?.message || 'Failed to fetch credits',
      });
    }
  }

  if (request.method === 'GET' && requestUrl.pathname === '/v1/billing/history') {
    try {
      const history = await billingService.getHistory(principal.id);
      return json(response, 200, { transactions: history });
    } catch (error) {
      console.error('[billing-history] request failed', { message: error?.message, status: error?.status });
      return json(response, Number.isInteger(error?.status) ? error.status : 500, {
        error: error?.message || 'Failed to fetch transaction history',
      });
    }
  }

  const uploadMatch = requestUrl.pathname.match(/^\/v1\/transcribe\/uploads\/([0-9a-f-]{36})(\/complete)?$/i);
  if (request.method === 'POST' && requestUrl.pathname === '/v1/transcribe/uploads') {
    try {
      const creditCheck = await billingService.checkCredits(principal.id, CREDIT_COSTS.TRANSCRIPTION);
      if (!creditCheck.sufficient) {
        return json(response, 402, {
          error: 'You need more credits to continue.',
          code: 'insufficient_credits',
          balance: creditCheck.balance,
          required: CREDIT_COSTS.TRANSCRIPTION,
        });
      }
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
    try {
      await billingService.deductCredits(principal.id, CREDIT_COSTS.TRANSCRIPTION, 'transcription', 'Audio transcription upload', requestId);
      const upload = transcriptionUploads.complete(uploadMatch[1], principal.id);
      const result = await transcriptionCore.transcribe(upload.stream);
      return json(response, 200, result);
    } catch (error) {
      console.error('[transcribe-upload] request failed', { message: error?.message, status: error?.status });
      return json(response, Number.isInteger(error?.status) ? error.status : 502, {
        error: error?.message || 'Transcription failed',
        code: error?.code,
        balance: error?.balance,
        required: error?.required,
        offset: error?.offset
      });
    } finally {
      transcriptionUploads.remove(uploadMatch[1]);
    }
  }
  if (request.method === 'POST' && requestUrl.pathname === '/v1/transcribe') {
    try {
      await billingService.deductCredits(principal.id, CREDIT_COSTS.TRANSCRIPTION, 'transcription', 'Audio transcription', requestId);
      transcriptionCore.validateUpload(request.headers);
      return json(response, 200, await transcriptionCore.transcribe(request));
    } catch (error) {
      console.error('[transcribe] request failed', { message: error?.message, status: error?.status });
      return json(response, error?.status && Number.isInteger(error.status) ? error.status : 502, {
        error: error?.message || 'Transcription failed',
        code: error?.code,
        balance: error?.balance,
        required: error?.required
      });
    }
  }

  if (request.method === 'POST' && requestUrl.pathname === '/v1/audio/generate-bgm') {
    try {
      const { prompt, durationSecs, beats, isEffect, chatId } = await collect(request);
      if (!chatId) throw Object.assign(new Error('chatId is strictly required to securely track and cleanup generated background music.'), { status: 400 });
      if (!prompt || !durationSecs) throw Object.assign(new Error('Missing prompt or durationSecs'), { status: 400 });
      await billingService.deductCredits(principal.id, CREDIT_COSTS.BGM_GENERATION, 'bgm_generation', `AI Background Music (${durationSecs}s)`, requestId);
      return json(response, 200, await audioGeneration.generateBgm(prompt, durationSecs, isEffect, chatId));
    } catch (error) {
      console.error('[audio-generate-bgm] request failed', { message: error?.message });
      return json(response, Number.isInteger(error?.status) ? error.status : 500, {
        error: error?.message || 'BGM generation failed',
        code: error?.code,
        balance: error?.balance,
        required: error?.required
      });
    }
  }

  if (request.method === 'POST' && requestUrl.pathname === '/v1/audio/fetch-sfx') {
    try {
      const { prompt } = await collect(request);
      if (!prompt) throw Object.assign(new Error('Missing prompt'), { status: 400 });
      await billingService.deductCredits(principal.id, CREDIT_COSTS.SFX_FETCH, 'sfx_fetch', `Fetch SFX: ${prompt.slice(0, 30)}`, requestId);
      return json(response, 200, await audioGeneration.fetchSfx(prompt));
    } catch (error) {
      console.error('[audio-fetch-sfx] request failed', { message: error?.message });
      return json(response, Number.isInteger(error?.status) ? error.status : 500, {
        error: error?.message || 'SFX fetch failed',
        code: error?.code,
        balance: error?.balance,
        required: error?.required
      });
    }
  }

  if (request.method === 'POST' && requestUrl.pathname === '/v1/chat/completion') {
    try {
      const payload = await collect(request, 5 * 1024 * 1024);
      await billingService.deductCredits(principal.id, CREDIT_COSTS.CHAT_COMPLETION, 'chat_completion', 'AI Chat completion', requestId);
      response.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive'
      });
      const finalRes = await agentDecisionProvider.chatCompletion(payload, (status) => {
        response.write(`event: status\ndata: ${JSON.stringify({ status })}\n\n`);
      });
      response.write(`event: done\ndata: ${JSON.stringify(finalRes)}\n\n`);
      return response.end();
    } catch (error) {
      console.error('[chat-completion] request failed', { message: error?.message, status: error?.status, code: error?.code });
      if (!response.headersSent) {
        return json(response, Number.isInteger(error?.status) ? error.status : 500, {
          error: error?.message || 'Failed to generate chat completion.',
          code: error?.code || 'chat_completion_failed',
          balance: error?.balance,
          required: error?.required,
          requestId: correlationId(request),
        });
      } else {
        response.write(`event: error\ndata: ${JSON.stringify({ error: error?.message || 'Failed' })}\n\n`);
        return response.end();
      }
    }
  }

  if (request.method === 'POST' && requestUrl.pathname === '/v1/projects/sync') {
    try {
      const projectData = await collect(request);
      if (!projectData.id) throw Object.assign(new Error('Missing project id'), { status: 400 });

      const { createClient } = await import('@supabase/supabase-js');
      const supabase = createClient(
        process.env.SUPABASE_URL || '',
        process.env.SUPABASE_SECRET_KEY || ''
      );

      // Upsert the project in the chats table
      // We use the service_role key to bypass RLS, ensuring we can insert even if the Clerk user ID is not a valid auth.users UUID
      const { error } = await supabase.from('chats')
        .upsert({
          id: projectData.id,
          title: projectData.title || 'New Chat',
          user_id: principal.sub,
          project_data: projectData,
        }, { onConflict: 'id' });

      if (error) throw Object.assign(new Error(error.message), { status: 500 });

      // Background Cleanup: Remove chat data and assets older than 20 chats
      (async () => {
        try {
          const { data: oldChats } = await supabase.from('chats')
            .select('id')
            .eq('user_id', principal.sub)
            .order('created_at', { ascending: false })
            .range(20, 1000); // 21st and beyond

          if (oldChats && oldChats.length > 0) {
            const oldChatIds = oldChats.map(c => c.id);

            const bgmBucket = process.env.SFX_S3_BUCKET || 'trimm-sfx-cache';

            // Delete BGM files for these chats
            for (const oldChatId of oldChatIds) {
              const { data: bgmFiles } = await supabase.storage.from(bgmBucket).list(`bgm/${oldChatId}`);
              if (bgmFiles && bgmFiles.length > 0) {
                const bgmPaths = bgmFiles.map(f => `bgm/${oldChatId}/${f.name}`);
                await supabase.storage.from(bgmBucket).remove(bgmPaths);
              }
            }

            // Finally, delete the old chats (this cascades to checkpoints)
            await supabase.from('chats').delete().in('id', oldChatIds);
          }
        } catch (cleanupError) {
          console.error('[projects-sync] Background cleanup failed:', cleanupError);
        }
      })();

      return json(response, 200, { success: true });
    } catch (error) {
      console.error('[projects-sync] request failed', { message: error?.message });
      return json(response, Number.isInteger(error?.status) ? error.status : 500, { error: error?.message || 'Project sync failed' });
    }
  }

  if (request.method === 'GET' && requestUrl.pathname === '/v1/projects') {
    try {
      const { createClient } = await import('@supabase/supabase-js');
      const supabase = createClient(
        process.env.SUPABASE_URL || '',
        process.env.SUPABASE_SECRET_KEY || ''
      );

      const { data, error } = await supabase.from('chats')
        .select('project_data')
        .eq('user_id', principal.sub)
        .not('project_data', 'is', null);

      if (error) throw Object.assign(new Error(error.message), { status: 500 });

      const projects = data.map(row => {
        const pd = row.project_data;
        let title = pd.title;
        if (title === 'New Edit' || title === 'Talking head edit' || !title) {
          const words = pd.analysis?.words || [];
          const text = words.slice(0, 4).map(w => w.text).join(' ');
          title = text ? text : 'Untitled Chat';
        }
        return { id: pd.id, title: title, modifiedAt: pd.analysis?.completedAtEpochMs || 0 };
      });
      projects.sort((a, b) => b.modifiedAt - a.modifiedAt);
      return json(response, 200, { projects });
    } catch (error) {
      console.error('[projects-get] request failed', { message: error?.message });
      return json(response, Number.isInteger(error?.status) ? error.status : 500, { error: error?.message || 'Project fetch failed' });
    }
  }

  if (request.method === 'GET' && requestUrl.pathname.match(/^\/v1\/projects\/([^\/]+)$/)) {
    try {
      const projectId = requestUrl.pathname.split('/')[3];

      const { createClient } = await import('@supabase/supabase-js');
      const supabase = createClient(
        process.env.SUPABASE_URL || '',
        process.env.SUPABASE_SECRET_KEY || ''
      );

      const { data, error } = await supabase.from('chats')
        .select('project_data')
        .eq('id', projectId)
        .eq('user_id', principal.sub)
        .single();

      if (error || !data) throw Object.assign(new Error(error?.message || 'Not found'), { status: 404 });

      return json(response, 200, data.project_data);
    } catch (error) {
      console.error('[project-get] request failed', { message: error?.message });
      return json(response, Number.isInteger(error?.status) ? error.status : 500, { error: error?.message || 'Project fetch failed' });
    }
  }

  if (request.method === 'DELETE' && requestUrl.pathname.match(/^\/v1\/projects\/([^\/]+)$/)) {
    try {
      const projectId = requestUrl.pathname.split('/')[3];

      const { createClient } = await import('@supabase/supabase-js');
      const supabase = createClient(
        process.env.SUPABASE_URL || '',
        process.env.SUPABASE_SECRET_KEY || ''
      );

      const { error } = await supabase.from('chats')
        .delete()
        .eq('id', projectId)
        .eq('user_id', principal.sub);

      if (error) throw Object.assign(new Error(error.message), { status: 500 });

      return json(response, 204, {});
    } catch (error) {
      console.error('[project-delete] request failed', { message: error?.message });
      return json(response, Number.isInteger(error?.status) ? error.status : 500, { error: error?.message || 'Project deletion failed' });
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
