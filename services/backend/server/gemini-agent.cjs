'use strict';

const { GoogleGenAI } = require('@google/genai');

function createGeminiDecisionProvider({
  apiKey = process.env.GEMINI_API_KEY,
  model = process.env.GEMINI_MODEL || 'gemini-3.6-flash',
  client,
} = {}) {
  if (model !== 'gemini-3.6-flash') throw new Error('The video agent is pinned to gemini-3.6-flash.');
  const ai = client || (apiKey ? new GoogleGenAI({ apiKey }) : null);
  async function generate({ systemPolicy, contents, temperature }) {
    if (!ai) throw Object.assign(new Error('GEMINI_API_KEY is not configured'), { status: 503, code: 'gemini_unconfigured' });
    const response = await ai.models.generateContent({
      model,
      contents,
      config: {
        systemInstruction: systemPolicy,
        responseMimeType: 'application/json',
        temperature,
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
