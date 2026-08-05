'use strict';

const { GoogleGenAI } = require('@google/genai');
const { AGENT_DECISION_SCHEMA, AGENT_TOOLS } = require('./agent-decision-schema.cjs');
const { geminiUsage } = require('./model-usage.cjs');

function createGeminiDecisionProvider({
  apiKey = process.env.GEMINI_API_KEY,
  model = process.env.GEMINI_MODEL || 'gemini-3.6-flash',
  client,
} = {}) {
  if (model !== 'gemini-3.6-flash') throw new Error('The video agent is pinned to gemini-3.6-flash.');
  const ai = client || (apiKey ? new GoogleGenAI({ apiKey }) : null);
  const fileCache = new Map();
  async function videoPart(item) {
    if (item.providerFile?.expiresAt > Date.now()) {
      fileCache.set(item.id, { uri: item.providerFile.uri, expiresAt: item.providerFile.expiresAt });
    }
    const cached = fileCache.get(item.id);
    if (cached && (!cached.expiresAt || cached.expiresAt > Date.now())) {
      return { fileData: { fileUri: cached.uri, mimeType: 'video/mp4' } };
    }
    if (ai?.files?.upload && item.bytes && item.bytes.length > 0) {
      try {
        let file = await ai.files.upload({ file: new Blob([item.bytes], { type: 'video/mp4' }), config: { mimeType: 'video/mp4' } });
        for (let attempt = 0; file?.state && String(file.state) !== 'ACTIVE' && attempt < 60; attempt++) {
          if (String(file.state) === 'FAILED') throw new Error('Gemini file processing failed.');
          await new Promise((resolve) => setTimeout(resolve, 500));
          file = await ai.files.get({ name: file.name });
        }
        if (file?.uri) {
          const expiresAt = Number.isFinite(Date.parse(file.expirationTime))
            ? Date.parse(file.expirationTime) : Date.now() + 47 * 60 * 60 * 1000;
          fileCache.set(item.id, { uri: file.uri, expiresAt });
          if (typeof item.persistProviderFile === 'function') {
            await item.persistProviderFile({ provider: 'google-gemini', uri: file.uri, name: file.name, expiresAt });
          }
          return { fileData: { fileUri: file.uri, mimeType: file.mimeType || 'video/mp4' } };
        }
      } catch (error) {
        throw Object.assign(new Error('Video upload failed: ' + (error.message || error.toString())), { status: 502, code: 'upload_failed' });
      }
    }
    if (!item.bytes) {
      throw Object.assign(new Error('Video evidence media bytes are missing or expired.'), { status: 409, code: 'evidence_bytes_missing' });
    }
    const data = Buffer.isBuffer(item.bytes) ? item.bytes.toString('base64') : Buffer.from(item.bytes).toString('base64');
    return { inlineData: { mimeType: 'video/mp4', data } };
  }
  async function generate({
    systemPolicy, contents, temperature, purpose = 'planning',
    responseJsonSchema = AGENT_DECISION_SCHEMA, tools = true, usageMedia,
  }) {
    if (!ai) throw Object.assign(new Error('GEMINI_API_KEY is not configured'), { status: 503, code: 'gemini_unconfigured' });
    const response = await ai.models.generateContent({
      model,
      contents,
      config: {
        systemInstruction: systemPolicy,
        responseMimeType: 'application/json',
        ...(tools ? { tools: [{ functionDeclarations: AGENT_TOOLS }] } : {}),
        temperature,
        maxOutputTokens: 4096,
      },
    });
    const functionCall = response.functionCalls?.[0];
    if (!response.text && !functionCall) throw Object.assign(new Error('Gemini returned no decision.'), { status: 502, code: 'empty_gemini_response' });
    return {
      rawText: response.text || null,
      toolCall: functionCall ? { name: functionCall.name, args: functionCall.args || {}, ...(functionCall.id ? { id: functionCall.id } : {}) } : null,
      modelContent: response.candidates?.[0]?.content || (functionCall ? {
        role: 'model', parts: [{ functionCall }],
      } : { role: 'model', parts: [{ text: response.text || '' }] }),
      history: [...(Array.isArray(contents) ? contents : [{ role: 'user', parts: [{ text: String(contents) }] }]),
        response.candidates?.[0]?.content || (functionCall
          ? { role: 'model', parts: [{ functionCall }] }
          : { role: 'model', parts: [{ text: response.text || '' }] })],
      finishReason: response.candidates?.[0]?.finishReason || null,
      usage: geminiUsage(response, { purpose, model, media: usageMedia }),
    };
  }
  const decide = async ({ systemPolicy, inventory, evidence, history }) => generate({
    systemPolicy,
    contents: history || [{ role: 'user', parts: [
      ...(await Promise.all(evidence.map(videoPart))),
      { text: JSON.stringify({ inventory, evidence: evidence.map(({ bytes, providerFile, persistProviderFile, ...item }) => item) }) },
    ] }],
    temperature: 0.2,
    purpose: 'planning',
    usageMedia: evidence.length ? {
      sourceId: evidence.at(-1).sourceId, startMs: evidence.at(-1).startMs,
      endMs: evidence.at(-1).endMs, resolution: evidence.at(-1).resolution,
    } : {},
  });
  decide.toolResult = async ({ systemPolicy, inventory, evidence = [], history = [], toolCall, result }) => decide({
    systemPolicy, inventory, evidence,
    history: [...(history || []), { role: 'user', parts: [{ functionResponse: {
      name: toolCall.name,
      ...(toolCall.id ? { id: toolCall.id } : {}),
      response: result,
    } }] }],
  });
  decide.capabilities = Object.freeze({
    nativeVideoAudio: true,
    timestampGrounding: true,
    toolUse: true,
    structuredOutput: true,
    reusableCachedContext: false,
    primaryModel: model,
  });
  decide.repair = (request) => generate({
    systemPolicy: `You repair JSON for ${request.expectedSchema}.
Correct structure only. Never change editorial intent, operations, ranges, evidence references, or expected revision.
Return only the corrected JSON object.`,
    contents: JSON.stringify(request),
    temperature: 0,
    purpose: 'proposal_repair',
    responseJsonSchema: AGENT_DECISION_SCHEMA,
    tools: false,
  });
  return decide;
}

module.exports = { createGeminiDecisionProvider };
