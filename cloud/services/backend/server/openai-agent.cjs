'use strict';

const { openaiUsage } = require('./model-usage.cjs');
const { AGENT_TOOLS } = require('./agent-decision-schema.cjs');

function createOpenAIDecisionProvider({
  apiKey = process.env.LLM_API_KEY,
  baseURL = process.env.LLM_OPENAI_BASE_URL || 'https://api.moonshot.cn/v1',
  model = process.env.MODEL || 'moonshot-v1-32k',
} = {}) {
  
  async function generate({
    systemPolicy, contents, temperature, purpose = 'planning',
    responseFormat = null, tools = true,
  }) {
    if (!apiKey) throw Object.assign(new Error('API key is not configured'), { status: 503, code: 'provider_unconfigured' });
    
    // contents can be a string or an array of message objects.
    let messages = [];
    if (systemPolicy) {
      messages.push({ role: 'system', content: systemPolicy });
    }
    
    if (Array.isArray(contents)) {
      // Map history from gemini style if needed, but we can assume we send standard openai role/content
      messages = messages.concat(contents.map(c => {
        if (c.role === 'model') return { role: 'assistant', content: c.parts?.[0]?.text || '', tool_calls: c.parts?.filter(p => p.functionCall).map(p => ({ id: p.functionCall.id || 'call_123', type: 'function', function: { name: p.functionCall.name, arguments: JSON.stringify(p.functionCall.args) } })) };
        if (c.role === 'user') return { role: 'user', content: c.parts?.[0]?.text || '' };
        return c; // if already openai format
      }));
    } else {
      messages.push({ role: 'user', content: String(contents) });
    }

    const requestPayload = {
      model,
      messages,
      temperature,
      ...(responseFormat ? { response_format: responseFormat } : {}),
      ...(tools && AGENT_TOOLS.length > 0 ? { tools: AGENT_TOOLS.map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters } })) } : {}),
    };

    console.log('[DEBUG] OpenAI Request:', JSON.stringify(requestPayload, null, 2));

    let response;
    let lastErr;
    for (let attempt = 1; attempt <= 3; attempt++) {
      try {
        const res = await fetch(`${baseURL.replace(/\/+$/, '')}/chat/completions`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${apiKey}`
          },
          body: JSON.stringify(requestPayload)
        });
        
        if (!res.ok) {
          const errorText = await res.text();
          throw new Error(`OpenAI API error ${res.status}: ${errorText}`);
        }
        
        response = await res.json();
        console.log('[DEBUG] OpenAI Response:', JSON.stringify(response, null, 2));
        lastErr = null;
        break;
      } catch (err) {
        lastErr = err;
        console.error(`[DEBUG] OpenAI API Error (Attempt ${attempt}):`, err);
        if (attempt === 3) throw err;
        await new Promise((resolve) => setTimeout(resolve, 2000 * attempt));
      }
    }
    if (lastErr) throw lastErr;
    
    const choice = response.choices[0];
    const message = choice.message;
    const functionCalls = message.tool_calls || [];
    
    if (!message.content && functionCalls.length === 0) {
      throw Object.assign(new Error('Model returned no decision.'), { status: 502, code: 'empty_provider_response' });
    }
    
    return {
      rawText: message.content || null,
      toolCalls: functionCalls.map(tc => ({ name: tc.function.name, args: JSON.parse(tc.function.arguments), id: tc.id })),
      history: [...messages, message],
      finishReason: choice.finish_reason || null,
      usage: openaiUsage(response, { purpose, model }),
    };
  }

  const generateTextOverlayConfig = async ({ description, text }) => {
    // simplified from gemini version
    const prompt = `You are an AI video editor designing a native text overlay with vector graphics.
Your goal is to parse the user's description and generate a JSON blueprint (Generative Canvas DSL).
This blueprint defines the background shapes (rectangles, circles, starbursts), text styling, and layout.

Target text to display: "${text}"
Effect description: ${description}

Return ONLY a valid JSON blueprint matching the required schema.`;

    let history = [];
    for (let attempt = 1; attempt <= 3; attempt++) {
      const response = await generate({
        systemPolicy: "You are a Generative Canvas DSL design agent.",
        contents: history.length > 0 ? history : prompt,
        temperature: 0.4,
        purpose: 'text_overlay_generation',
        tools: false,
        responseFormat: { type: 'json_object' }
      });

      try {
        return JSON.parse(response.rawText);
      } catch (e) {
        if (attempt === 3) throw Object.assign(new Error("Failed to parse text overlay response"), { code: 'text_overlay_error' });
        history = [
          { role: 'user', content: prompt },
          { role: 'assistant', content: response.rawText || '' },
          { role: 'user', content: "The response was not valid JSON. Please fix it and output ONLY valid JSON. Error: " + e.message }
        ];
      }
    }
  };

  const generateTransitionConfig = async ({ description }) => {
    const prompt = `You are an AI video editor designing a custom Canvas-based video transition.
Your goal is to parse the user's description and generate a JSON blueprint (Generative Canvas DSL).
Return ONLY a valid JSON blueprint matching the required schema.

Effect description: ${description}`;

    let history = [];
    for (let attempt = 1; attempt <= 3; attempt++) {
      const response = await generate({
        systemPolicy: "You are a Generative Canvas DSL design agent.",
        contents: history.length > 0 ? history : prompt,
        temperature: 0.4,
        purpose: 'transition_generation',
        tools: false,
        responseFormat: { type: 'json_object' }
      });

      try {
        return JSON.parse(response.rawText);
      } catch (e) {
        if (attempt === 3) throw Object.assign(new Error("Failed to parse transition response"), { code: 'transition_error' });
        history = [
          { role: 'user', content: prompt },
          { role: 'assistant', content: response.rawText || '' },
          { role: 'user', content: "The response was not valid JSON. Please fix it and output ONLY valid JSON. Error: " + e.message }
        ];
      }
    }
  };

  const generatePlan = async ({ prompt }) => {
    const systemPolicy = `You are the Trimm Router Agent. Your job is to break down a complex video editing request into a sequence of simpler tasks.
Each task must be a natural language prompt that can be independently executed by a Worker Agent.
Use the 'dependsOn' field to enforce sequential execution.
If the request is too vague, return a clarification object instead of tasks.

Return ONLY a valid JSON matching the schema (tasks array or clarification object).`;

    let history = [];
    for (let attempt = 1; attempt <= 3; attempt++) {
      const response = await generate({
        systemPolicy,
        contents: history.length > 0 ? history : prompt,
        temperature: 0.1,
        purpose: 'planning',
        tools: false,
        responseFormat: { type: 'json_object' }
      });

      try {
        return JSON.parse(response.rawText);
      } catch (e) {
        if (attempt === 3) throw Object.assign(new Error("Failed to parse router plan response"), { code: 'router_plan_error' });
        history = [
          { role: 'user', content: prompt },
          { role: 'assistant', content: response.rawText || '' },
          { role: 'user', content: "The response was not valid JSON. Please fix it. Error: " + e.message }
        ];
      }
    }
  };

  const executeTask = async ({ intent, sources = [], transcript = [], existingOperations = [] }) => {
    const systemPolicy = `You are a specialized Worker Agent for a video editor.
Your job is to execute the user's specific editing intent by calling the appropriate tool(s).
DO NOT use the ask_clarification tool.
If you decide no edits are needed, or if you cannot execute the task, you MUST call the finish_without_proposal tool and thoroughly explain WHY in the reason field.

Available Sources:
${JSON.stringify(sources)}

Existing Operations (current project state):
${JSON.stringify(existingOperations)}

Transcript Data (use this to detect dead space, silences, and timings):
${JSON.stringify(transcript)}
`;

    const contents = [{ role: 'user', content: `Task Intent: ${intent}` }];

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
        const operationName = tc.name.startsWith('propose_') ? tc.name.replace('propose_', '') : tc.name;
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

        return { type: operationName, target, ...payload };
      })
    };
  };

  return { generateTextOverlayConfig, generateTransitionConfig, generatePlan, executeTask };
}

module.exports = { createOpenAIDecisionProvider };
