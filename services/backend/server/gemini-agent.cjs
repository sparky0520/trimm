'use strict';

const { GoogleGenAI } = require('@google/genai');

function createGeminiDecisionProvider({
  apiKey = process.env.GEMINI_API_KEY,
  model = process.env.GEMINI_MODEL || 'gemini-3.6-flash',
  escalationModel = process.env.GEMINI_ESCALATION_MODEL || 'gemini-3.1-pro-preview',
  client,
} = {}) {
  if (model !== 'gemini-3.6-flash') throw new Error('The video agent is pinned to gemini-3.6-flash.');
  if (escalationModel !== 'gemini-3.1-pro-preview') throw new Error('The ambiguity escalation model is pinned to gemini-3.1-pro-preview.');
  const ai = client || (apiKey ? new GoogleGenAI({ apiKey }) : null);
  async function generate({ systemPolicy, contents, temperature, selectedModel = model, cachedContent, tools }) {
    if (!ai) throw Object.assign(new Error('GEMINI_API_KEY is not configured'), { status: 503, code: 'gemini_unconfigured' });
    const response = await ai.models.generateContent({
      model: selectedModel,
      contents,
      config: {
        systemInstruction: systemPolicy,
        responseMimeType: 'application/json',
        temperature,
        ...(cachedContent ? { cachedContent } : {}),
        ...(tools?.length ? { tools } : {}),
      },
    });
    if (!response.text) throw Object.assign(new Error('Gemini returned no decision.'), { status: 502, code: 'empty_gemini_response' });
    return {
      rawText: response.text,
      finishReason: response.candidates?.[0]?.finishReason || null,
    };
  }
  const decide = ({ systemPolicy, inventory, evidence }) => generate({
    systemPolicy,
    contents: JSON.stringify({ inventory, evidence }),
    temperature: 0.2,
  });
  decide.escalate = ({ systemPolicy, inventory, evidence, cachedContent, tools }) => generate({
    systemPolicy,
    contents: JSON.stringify({ inventory, evidence, escalationReason: 'genuinely_ambiguous_editorial_decision' }),
    temperature: 0.1,
    selectedModel: escalationModel,
    cachedContent,
    tools,
  });
  decide.capabilities = Object.freeze({
    nativeVideoAudio: true,
    timestampGrounding: true,
    toolUse: true,
    structuredOutput: true,
    reusableCachedContext: true,
    primaryModel: model,
    escalationModel,
  });
  decide.repair = (request) => generate({
    systemPolicy: `You repair JSON for ${request.expectedSchema}.
Correct structure only. Never change editorial intent, operations, ranges, evidence references, or expected revision.
Return only the corrected JSON object.`,
    contents: JSON.stringify(request),
    temperature: 0,
  });
  return decide;
}

module.exports = { createGeminiDecisionProvider };
