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
    const requestPayload = {
      model,
      contents,
      config: {
        systemInstruction: systemPolicy,
        responseMimeType: 'application/json',
        ...(responseJsonSchema ? { responseJsonSchema: JSON.parse(JSON.stringify(responseJsonSchema)) } : {}),
        ...(tools ? { tools: [{ functionDeclarations: JSON.parse(JSON.stringify(AGENT_TOOLS)).map(t => ({ ...t, parametersJsonSchema: t.parameters, parameters: undefined })) }] } : {}),
        temperature,
        maxOutputTokens: 4096,
      },
    };

    const logPayload = JSON.parse(JSON.stringify(requestPayload));
    if (Array.isArray(logPayload.contents)) {
      logPayload.contents.forEach(c => {
        if (Array.isArray(c.parts)) {
          c.parts.forEach(p => {
            if (p.inlineData && p.inlineData.data) p.inlineData.data = '[REDACTED_BASE64]';
          });
        }
      });
    }
    console.log('[DEBUG] Gemini Request:', JSON.stringify(logPayload, null, 2));

    let response;
    try {
      response = await ai.models.generateContent(requestPayload);
      console.log('[DEBUG] Gemini Response:', JSON.stringify(response, null, 2));
    } catch (err) {
      console.error('[DEBUG] Gemini API Error:', err, JSON.stringify(err.details || err));
      throw err;
    }
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

  decide.generateGlsl = async ({ description, vfxType, previousCode, compileError }) => {
    const prompt = `You are an expert OpenGL ES 3.0 shader developer.
Create a custom fragment shader for an AndroidX Media3 GlEffect.
The video frames are provided as a uniform sampler2D uTexSampler, and the sampling coordinate is in vec2 vTexSamplingCoord.
Output the color to out vec4 outColor.
You also have uTime (float, seconds since presentation started) and uProgress (float, 0.0 to 1.0 progress within the effect's time range).
You can also define custom uniform floats and provide their values.

Target description: ${description}
VfxType: ${vfxType}

${previousCode ? `The previous code failed to compile:\n\n${previousCode}\n\nError: ${compileError}\n\nPlease fix the shader.` : ''}

Return a JSON object with:
- fragmentShader: The GLSL shader code string.
- uniforms: A map of string to float containing any custom uniforms you used.`;

    const response = await generate({
      systemPolicy: "You write GLSL shaders.",
      contents: prompt,
      temperature: 0.2,
      purpose: 'vfx_shader_generation',
      tools: false,
      responseJsonSchema: {
        type: 'object', properties: {
          fragmentShader: { type: 'string' },
          uniforms: { type: 'object', additionalProperties: { type: 'number' } }
        }, required: ['fragmentShader', 'uniforms']
      }
    });

    try {
      const parsed = JSON.parse(response.rawText);
      return parsed;
    } catch (e) {
      throw Object.assign(new Error("Failed to parse GLSL subagent response"), { code: 'glsl_subagent_error' });
    }
  };

  decide.generateTextOverlayConfig = async ({ description, text }) => {
    const prompt = `You are an AI video editor designing a native text overlay with vector graphics.
Your goal is to parse the user's description and generate a JSON blueprint (Generative Canvas DSL).
This blueprint defines the background shapes (rectangles, circles, starbursts), text styling, and layout.

Target text to display: "${text}"
Effect description: ${description}

Examples of descriptions to blueprints:

Description: "comic book BANG effect in the top left"
Output: {
  "backgroundShapes": [
    { "type": "starburst", "color": "#FFFF00", "strokeColor": "#FF0000", "strokeWidth": 20, "points": 16, "innerRadius": 250, "outerRadius": 450, "width": 0, "height": 0, "cornerRadius": 0 }
  ],
  "textStyle": { "color": "#000000", "strokeColor": "#FFFFFF", "strokeWidth": 12, "shadowRadius": 0, "shadowColor": "" },
  "layout": { "posX": -0.8, "posY": -0.8, "scale": 1.5, "rotation": -15.0 }
}

Description: "elegant title card floating at the bottom"
Output: {
  "backgroundShapes": [
    { "type": "rectangle", "color": "#80000000", "strokeColor": "", "strokeWidth": 0, "points": 0, "innerRadius": 0, "outerRadius": 0, "width": 800, "height": 200, "cornerRadius": 20 }
  ],
  "textStyle": { "color": "#FFFFFF", "strokeColor": "", "strokeWidth": 0, "shadowRadius": 10, "shadowColor": "#000000" },
  "layout": { "posX": 0.0, "posY": 0.7, "scale": 1.0, "rotation": 0.0 }
}

Return ONLY a JSON blueprint matching the schema.`;

    const response = await generate({
      systemPolicy: "You are a Generative Canvas DSL design agent.",
      contents: prompt,
      temperature: 0.4,
      purpose: 'text_overlay_generation',
      tools: false,
      responseJsonSchema: {
        type: 'object', properties: {
          backgroundShapes: {
            type: 'array', items: {
              type: 'object', properties: {
                type: { type: 'string', description: 'rectangle, circle, or starburst' },
                color: { type: 'string', description: 'Hex color, e.g. #FFFF00 or #80000000' },
                strokeColor: { type: 'string' },
                strokeWidth: { type: 'number' },
                points: { type: 'integer' },
                innerRadius: { type: 'number' },
                outerRadius: { type: 'number' },
                width: { type: 'number' },
                height: { type: 'number' },
                cornerRadius: { type: 'number' }
              }, required: ['type', 'color', 'strokeColor', 'strokeWidth', 'points', 'innerRadius', 'outerRadius', 'width', 'height', 'cornerRadius']
            }
          },
          textStyle: {
            type: 'object', properties: {
              color: { type: 'string' },
              strokeColor: { type: 'string' },
              strokeWidth: { type: 'number' },
              shadowRadius: { type: 'number' },
              shadowColor: { type: 'string' }
            }, required: ['color', 'strokeColor', 'strokeWidth', 'shadowRadius', 'shadowColor']
          },
          layout: {
            type: 'object', properties: {
              posX: { type: 'number' },
              posY: { type: 'number' },
              scale: { type: 'number' },
              rotation: { type: 'number' }
            }, required: ['posX', 'posY', 'scale', 'rotation']
          }
        }, required: ['backgroundShapes', 'textStyle', 'layout']
      }
    });

    try {
      return JSON.parse(response.rawText);
    } catch (e) {
      throw Object.assign(new Error("Failed to parse text overlay subagent response"), { code: 'text_overlay_subagent_error' });
    }
  };

  return decide;
}

module.exports = { createGeminiDecisionProvider };
