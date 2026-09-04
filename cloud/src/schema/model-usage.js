
import crypto from 'node:crypto';

const GEMINI_36_FLASH_MICROS_PER_TOKEN = Object.freeze({
  input: 1.5,
  output: 7.5,
  cached: 0.15,
});

function nonNegativeInteger(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? Math.trunc(number) : 0;
}

function geminiUsage(response, { purpose, model = 'gemini-3.6-flash', callId, media } = {}) {
  const metadata = response?.usageMetadata || {};
  const inputTokens = nonNegativeInteger(metadata.promptTokenCount);
  const outputTokens = nonNegativeInteger(metadata.candidatesTokenCount);
  const thinkingTokens = nonNegativeInteger(metadata.thoughtsTokenCount);
  const cachedTokens = nonNegativeInteger(metadata.cachedContentTokenCount);
  const detail = Array.isArray(metadata.promptTokensDetails) ? metadata.promptTokensDetails : [];
  const modalityTokens = (name) => detail.filter((item) => String(item?.modality || '').toUpperCase() === name)
    .reduce((sum, item) => sum + nonNegativeInteger(item.tokenCount), 0);
  const estimatedCostMicros = Math.ceil(
    Math.max(0, inputTokens - cachedTokens) * GEMINI_36_FLASH_MICROS_PER_TOKEN.input +
    cachedTokens * GEMINI_36_FLASH_MICROS_PER_TOKEN.cached +
    (outputTokens) * GEMINI_36_FLASH_MICROS_PER_TOKEN.output
  );
  return {
    callId: String(callId || response?.responseId || crypto.randomUUID()).slice(0, 200),
    provider: 'google-gemini',
    model,
    purpose: String(purpose || 'planning').slice(0, 100),
    inputTokens,
    outputTokens,
    thinkingTokens,
    cachedTokens,
    totalTokens: inputTokens + outputTokens + thinkingTokens,
    estimatedCostMicros,
    textTokens: modalityTokens('TEXT'),
    videoTokens: modalityTokens('VIDEO'),
    occurredAt: new Date().toISOString(),
    ...(media || {}),
    costEstimated: true,
  };
}

function usageSummary(entries = []) {
  if (!Array.isArray(entries)) entries = Array.isArray(entries?.calls) ? entries.calls : [];
  const unique = [...new Map(entries.filter(Boolean).map((entry) => [entry.callId, entry])).values()];
  return {
    calls: unique,
    inputTokens: unique.reduce((sum, item) => sum + nonNegativeInteger(item.inputTokens), 0),
    outputTokens: unique.reduce((sum, item) => sum + nonNegativeInteger(item.outputTokens), 0),
    thinkingTokens: unique.reduce((sum, item) => sum + nonNegativeInteger(item.thinkingTokens), 0),
    cachedTokens: unique.reduce((sum, item) => sum + nonNegativeInteger(item.cachedTokens), 0),
    totalTokens: unique.reduce((sum, item) => sum + nonNegativeInteger(item.totalTokens), 0),
    estimatedCostMicros: unique.reduce((sum, item) => sum + nonNegativeInteger(item.estimatedCostMicros), 0),
  };
}
function openaiUsage(response, { purpose, model = 'moonshot-v1-32k', callId, media } = {}) {
  const metadata = response?.usage || {};
  const inputTokens = nonNegativeInteger(metadata.prompt_tokens);
  const outputTokens = nonNegativeInteger(metadata.completion_tokens);
  const totalTokens = nonNegativeInteger(metadata.total_tokens);
  
  // Approximate pricing for moonshot-v1-32k (replace with accurate if needed)
  const estimatedCostMicros = Math.ceil(
    inputTokens * 0.012 + outputTokens * 0.012
  );

  return {
    callId: String(callId || response?.id || crypto.randomUUID()).slice(0, 200),
    provider: 'openai-compatible',
    model,
    purpose: String(purpose || 'planning').slice(0, 100),
    inputTokens,
    outputTokens,
    thinkingTokens: 0,
    cachedTokens: 0,
    totalTokens,
    estimatedCostMicros,
    textTokens: totalTokens,
    videoTokens: 0,
    occurredAt: new Date().toISOString(),
    ...(media || {}),
    costEstimated: true,
  };
}

export {  geminiUsage, openaiUsage, usageSummary  };
