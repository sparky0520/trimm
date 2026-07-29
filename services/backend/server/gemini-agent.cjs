'use strict';

const { GoogleGenAI } = require('@google/genai');

function createGeminiDecisionProvider({
  apiKey = process.env.GEMINI_API_KEY,
  model = process.env.GEMINI_MODEL || 'gemini-3.6-flash',
  client,
} = {}) {
  if (model !== 'gemini-3.6-flash') throw new Error('The video agent is pinned to gemini-3.6-flash.');
  const ai = client || (apiKey ? new GoogleGenAI({ apiKey }) : null);
  return async ({ systemPolicy, inventory, evidence }) => {
    if (!ai) throw Object.assign(new Error('GEMINI_API_KEY is not configured'), { status: 503, code: 'gemini_unconfigured' });
    const response = await ai.models.generateContent({
      model,
      contents: JSON.stringify({ inventory, evidence }),
      config: {
        systemInstruction: systemPolicy,
        responseMimeType: 'application/json',
        temperature: 0.2,
      },
    });
    if (!response.text) throw Object.assign(new Error('Gemini returned no decision.'), { status: 502, code: 'empty_gemini_response' });
    return JSON.parse(response.text);
  };
}

module.exports = { createGeminiDecisionProvider };
