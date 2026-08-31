'use strict';

const { GoogleGenAI } = require('@google/genai');
const { geminiUsage } = require('./model-usage.cjs');
const { AGENT_TOOLS } = require('./agent-decision-schema.cjs');

function createGeminiDecisionProvider({
  apiKey = process.env.GEMINI_API_KEY,
  model = process.env.GEMINI_MODEL || 'gemini-3.6-flash',
  client,
} = {}) {
  if (model !== 'gemini-3.6-flash') throw new Error('The video agent is pinned to gemini-3.6-flash.');
  const ai = client || (apiKey ? new GoogleGenAI({ apiKey }) : null);

  async function generate({
    systemPolicy, contents, temperature, purpose = 'planning',
    responseJsonSchema = null, tools = true, usageMedia, cachedContent = null,
  }) {
    if (!ai) throw Object.assign(new Error('GEMINI_API_KEY is not configured'), { status: 503, code: 'gemini_unconfigured' });
    const requestPayload = {
      model,
      contents,
      config: {
        systemInstruction: systemPolicy,
        ...(responseJsonSchema ? { 
          responseMimeType: 'application/json',
          responseJsonSchema: JSON.parse(JSON.stringify(responseJsonSchema)) 
        } : {}),
        temperature,
        maxOutputTokens: 4096,
        ...(cachedContent ? { cachedContent } : {}),
        ...(tools ? { tools: [{ functionDeclarations: JSON.parse(JSON.stringify(AGENT_TOOLS)).map(t => ({ ...t, parametersJsonSchema: t.parameters, parameters: undefined })) }] } : {}),
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
    let lastErr;
    for (let attempt = 1; attempt <= 3; attempt++) {
      try {
        response = await ai.models.generateContent(requestPayload);
        console.log('[DEBUG] Gemini Response:', JSON.stringify(response, null, 2));
        lastErr = null;
        break;
      } catch (err) {
        lastErr = err;
        console.error(`[DEBUG] Gemini API Error (Attempt ${attempt}):`, err, JSON.stringify(err.details || err));
        if (attempt === 3) throw err;
        await new Promise((resolve) => setTimeout(resolve, 2000 * attempt));
      }
    }
    if (lastErr) throw lastErr;
    const functionCalls = response.functionCalls || [];
    if (!response.text && functionCalls.length === 0) throw Object.assign(new Error('Gemini returned no decision.'), { status: 502, code: 'empty_gemini_response' });
    return {
      rawText: response.text || null,
      toolCalls: functionCalls.map(fc => ({ name: fc.name, args: fc.args || {}, ...(fc.id ? { id: fc.id } : {}) })),
      modelContent: response.candidates?.[0]?.content || (functionCalls.length > 0 ? {
        role: 'model', parts: functionCalls.map(functionCall => ({ functionCall })),
      } : { role: 'model', parts: [{ text: response.text || '' }] }),
      history: [...(Array.isArray(contents) ? contents : [{ role: 'user', parts: [{ text: String(contents) }] }]),
        response.candidates?.[0]?.content || (functionCalls.length > 0
          ? { role: 'model', parts: functionCalls.map(functionCall => ({ functionCall })) }
          : { role: 'model', parts: [{ text: response.text || '' }] })],
      finishReason: response.candidates?.[0]?.finishReason || null,
      usage: geminiUsage(response, { purpose, model, media: usageMedia }),
    };
  }
  const generateTextOverlayConfig = async ({ description, text }) => {
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
  "textStyle": { "color": "#000000", "strokeColor": "#FFFFFF", "strokeWidth": 12, "shadowRadius": 0, "shadowColor": "", "fontFamily": "comic" },
  "layout": { "posX": -0.8, "posY": -0.8, "scale": 1.5, "rotation": -15.0 }
}

Description: "elegant title card floating at the bottom"
Output: {
  "backgroundShapes": [
    { "type": "rectangle", "color": "#80000000", "strokeColor": "", "strokeWidth": 0, "points": 0, "innerRadius": 0, "outerRadius": 0, "width": 800, "height": 200, "cornerRadius": 20 }
  ],
  "textStyle": { "color": "#FFFFFF", "strokeColor": "", "strokeWidth": 0, "shadowRadius": 10, "shadowColor": "#000000", "fontFamily": "elegant" },
  "layout": { "posX": 0.0, "posY": 0.7, "scale": 1.0, "rotation": 0.0 }
}

Return ONLY a JSON blueprint matching the schema.`;

    let history = [];
    for (let attempt = 1; attempt <= 3; attempt++) {
      const response = await generate({
        systemPolicy: "You are a Generative Canvas DSL design agent.",
        contents: history.length > 0 ? history : prompt,
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
                shadowColor: { type: 'string' },
                fontFamily: { type: 'string', description: 'Choose from: comic, neon, typewriter, elegant, plain' }
              }, required: ['color', 'strokeColor', 'strokeWidth', 'shadowRadius', 'shadowColor', 'fontFamily']
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
        if (attempt === 3) {
          throw Object.assign(new Error("Failed to parse text overlay subagent response"), { code: 'text_overlay_subagent_error' });
        }
        history = [
          { role: 'user', parts: [{ text: prompt }] },
          { role: 'model', parts: [{ text: response.rawText || '' }] },
          { role: 'user', parts: [{ text: "The response was not valid JSON. Please fix it and output ONLY valid JSON matching the schema. Error: " + e.message }] }
        ];
      }
    }
  };

  const generateTransitionConfig = async ({ description }) => {
    const prompt = `You are an AI video editor designing a custom Canvas-based video transition.
Your goal is to parse the user's description and generate a JSON blueprint (Generative Canvas DSL).
This blueprint defines simple shapes that animate to cover the screen and act as a transition wipe.

Effect description: ${description}

Examples of descriptions to blueprints:

Description: "a black circle that grows to cover the screen"
Output: {
  "shapes": [
    { "type": "circle", "color": "#000000", "radius": 2000.0, "animateScale": true }
  ]
}

Return ONLY a JSON blueprint matching the schema.`;

    let history = [];
    for (let attempt = 1; attempt <= 3; attempt++) {
      const response = await generate({
        systemPolicy: "You are a Generative Canvas DSL design agent.",
        contents: history.length > 0 ? history : prompt,
        temperature: 0.4,
        purpose: 'transition_generation',
        tools: false,
        responseJsonSchema: {
          type: 'object', properties: {
            shapes: {
              type: 'array', items: {
                type: 'object', properties: {
                  type: { type: 'string', description: 'rectangle, circle' },
                  color: { type: 'string' },
                  posX: { type: 'number' },
                  posY: { type: 'number' },
                  radius: { type: 'number' },
                  width: { type: 'number' },
                  height: { type: 'number' },
                  animateScale: { type: 'boolean' }
                }, required: ['type', 'color']
              }
            }
          }, required: ['shapes']
        }
      });

      try {
        return JSON.parse(response.rawText);
      } catch (e) {
        if (attempt === 3) {
          throw Object.assign(new Error("Failed to parse transition subagent response"), { code: 'transition_subagent_error' });
        }
        history = [
          { role: 'user', parts: [{ text: prompt }] },
          { role: 'model', parts: [{ text: response.rawText || '' }] },
          { role: 'user', parts: [{ text: "The response was not valid JSON. Please fix it and output ONLY valid JSON matching the schema. Error: " + e.message }] }
        ];
      }
    }
  };

  const generatePlan = async ({ prompt }) => {
    const systemPolicy = `You are the Trimm Router Agent. Your job is to break down a complex video editing request into a sequence of simpler tasks.
Each task must be a natural language prompt that can be independently executed by a Worker Agent.
Use the 'dependsOn' field to enforce sequential execution if a task relies on the completion of another task (e.g. adding captions must happen AFTER dead space is removed, since removing space changes timing). If tasks don't depend on each other, they can run in parallel (empty dependsOn).
If the request is too vague or ambiguous to confidently break down into tasks, you MUST return a clarification object instead of tasks to ask the user to choose a direction.

Return ONLY a JSON blueprint matching the schema.`;

    let history = [];
    for (let attempt = 1; attempt <= 3; attempt++) {
      const response = await generate({
        systemPolicy,
        contents: history.length > 0 ? history : prompt,
        temperature: 0.1,
        purpose: 'planning',
        tools: false,
        responseJsonSchema: {
          type: 'object', properties: {
            tasks: {
              type: 'array', items: {
                type: 'object', properties: {
                  id: { type: 'string', description: 'Unique identifier for this task (e.g. task_1)' },
                  description: { type: 'string', description: 'The natural language sub-prompt for the worker agent' },
                  dependsOn: { type: 'array', items: { type: 'string' }, description: 'List of task ids that must finish before this one starts' }
                }, required: ['id', 'description', 'dependsOn']
              }
            },
            clarification: {
              type: 'object', properties: {
                type: { type: 'string' },
                questions: {
                  type: 'array', items: {
                    type: 'object', properties: {
                      id: { type: 'string' },
                      prompt: { type: 'string' },
                      options: {
                        type: 'array', items: {
                          type: 'object', properties: {
                            id: { type: 'string' }, label: { type: 'string' }, description: { type: 'string' }
                          }, required: ['id', 'label', 'description']
                        }
                      },
                      recommendedOptionId: { type: 'string' }
                    }, required: ['id', 'prompt', 'options']
                  }
                }
              }, required: ['type', 'questions']
            }
          }
        }
      });

      try {
        return JSON.parse(response.rawText);
      } catch (e) {
        if (attempt === 3) {
          throw Object.assign(new Error("Failed to parse router plan response"), { code: 'router_plan_error' });
        }
        history = [
          { role: 'user', parts: [{ text: prompt }] },
          { role: 'model', parts: [{ text: response.rawText || '' }] },
          { role: 'user', parts: [{ text: "The response was not valid JSON. Please fix it and output ONLY valid JSON matching the schema. Error: " + e.message }] }
        ];
      }
    }
  };

  const executeTask = async ({ intent, sources = [], transcript = [], existingOperations = [], videoFiles = [] }) => {
    const systemPolicy = `You are a specialized Worker Agent for a video editor.
Your job is to execute the user's specific editing intent by calling the appropriate tool(s).
DO NOT use the ask_clarification tool.
If you decide no edits are needed, or if you cannot execute the task, you MUST call the finish_without_proposal tool and thoroughly explain WHY in the reason field (e.g., "I found a 2-second pause at 0:45, but it felt like a natural dramatic pause, so I left it"). Do not just reply with text.

Background music (propose_add_bgm) is automatically looped by the app. Do not try to time events in BGM. If the user requests a perfectly timed musical event (e.g., "epic bass drop", "orchestral crescendo swell", or "fade out" at a specific timestamp or when someone appears), you MUST use the propose_add_bgm_effect tool for longer effects (10-12s) or propose_add_sfx for very short effects, placing it exactly at the requested startMs using the transcript or visual context.

Available Sources:
${JSON.stringify(sources)}

Existing Operations (current project state):
${JSON.stringify(existingOperations)}

Transcript Data (use this to detect dead space, silences, and timings):
${JSON.stringify(transcript)}
`;

    let parts = [{ text: `Task Intent: ${intent}` }];
    
    if (videoFiles && videoFiles.length > 0) {
      const fs = require('fs');
      for (const file of videoFiles) {
        if (file.path && fs.existsSync(file.path)) {
          parts.unshift({
            inlineData: {
              data: fs.readFileSync(file.path).toString('base64'),
              mimeType: file.mimeType || 'video/mp4'
            }
          });
        }
      }
    }

    const contents = [{ role: 'user', parts }];

    const response = await generate({
      systemPolicy,
      contents,
      temperature: 0.1,
      purpose: 'execution',
      tools: true,
    });

    const failure = response.toolCalls.find(tc => tc.name === 'finish_without_proposal');
    if (failure) {
      return { type: "edit_program", operations: [] };
    }

    if (!response.toolCalls || response.toolCalls.length === 0) {
      if (response.rawText) {
        return { type: "edit_program", operations: [] };
      }
      throw Object.assign(new Error("Worker agent did not return any operations."), { code: 'worker_execution_error' });
    }

    const clarification = response.toolCalls.find(tc => tc.name === 'ask_clarification');
    if (clarification) {
      return { type: "clarification", questions: clarification.args.questions };
    }

    const validOperations = response.toolCalls.filter(tc => tc.name.startsWith('propose_') || !['ask_clarification', 'finalize_edit_program', 'finish_without_proposal', 'remove_draft_operation'].includes(tc.name));

    return {
      type: "edit_program",
      operations: validOperations.map(tc => {
        // Strip the propose_ prefix for the client operation name
        const operationName = tc.name.startsWith('propose_') ? tc.name.replace('propose_', '') : tc.name;
        // Unwrap the proposal envelope to provide flattened attributes with a 'type' discriminator
        let payload = tc.args.operation || tc.args;
        
        let target = undefined;
        if (payload.sourceId !== undefined && payload.startMs !== undefined && payload.endMs !== undefined) {
          target = { sourceId: payload.sourceId, startMs: payload.startMs, endMs: payload.endMs };
          const { sourceId, startMs, endMs, ...rest } = payload;
          payload = rest;
        }

        if (operationName === 'set_dialogue_loudness') {
          return { type: 'set_audio_levels', dialogueLufs: payload.targetLufs, reason: payload.reason, target };
        }
        if (operationName === 'set_music_ducking') {
          return { type: 'set_audio_levels', musicDuckDb: payload.duckDb, attackMs: payload.attackMs, releaseMs: payload.releaseMs, reason: payload.reason, target };
        }
        if (operationName.startsWith('adjust_')) {
          const param = operationName.replace('adjust_', '');
          return { type: 'set_color_grade', parameter: param, value: payload.value, reason: payload.reason, target };
        }

        return { type: operationName, target, ...payload };
      })
    };
  };

  return { generateTextOverlayConfig, generateTransitionConfig, generatePlan, executeTask };
}

module.exports = { createGeminiDecisionProvider };
