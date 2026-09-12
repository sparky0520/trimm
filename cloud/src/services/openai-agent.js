
import { openaiUsage } from '../schema/model-usage.js';
import { AGENT_TOOLS } from '../schema/agent-decision-schema.js';

import OpenAI from 'openai';

function createOpenAIDecisionProvider({
  apiKey = process.env.LLM_API_KEY,
  baseURL = process.env.LLM_OPENAI_BASE_URL || 'https://api.moonshot.ai/v1',
  model = process.env.MODEL || 'kimi-k3',
} = {}) {

  const openai = new OpenAI({
    apiKey,
    baseURL,
  });

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
      messages = messages.concat(contents);
    } else {
      messages.push({ role: 'user', content: String(contents) });
    }

    const requestPayload = {
      model,
      messages,
      ...(responseFormat ? { response_format: responseFormat } : {}),
      ...(tools && AGENT_TOOLS.length > 0 ? { tools: AGENT_TOOLS.map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters } })) } : {}),
    };

    console.log('[DEBUG] OpenAI Request:', JSON.stringify(requestPayload, null, 2));

    let response;
    try {
      response = await openai.chat.completions.create(requestPayload, { maxRetries: 4 });
      console.log('[DEBUG] OpenAI Response:', JSON.stringify(response, null, 2));
    } catch (err) {
      console.error(`[DEBUG] OpenAI API Error:`, err);
      throw err;
    }

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

  const generateTextOverlayConfig = async ({ description, text, videoWidth = 1920, videoHeight = 1080 }) => {
    // simplified from gemini version
    const prompt = `You are an AI video editor designing a native text overlay with vector graphics.
Your goal is to parse the user's description and generate a JSON blueprint (Generative Canvas DSL).
This blueprint defines the background shapes (rectangles, circles, starbursts), text styling, and layout.

The target video has dimensions: ${videoWidth}x${videoHeight} (width x height). Use these dimensions to accurately position and size elements, especially for safe margins and alignment.

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

  const chatCompletion = async ({ history = [], sources = [], transcript = [], existingOperations = [] }) => {
    const systemPolicy = `You are an AI video editor Agent.
Your job is to execute the user's editing requests by proposing operations using the appropriate tool(s).
You have access to the chat history, transcript, source media, and the current project state (existing operations).

Available Sources:
${JSON.stringify(sources)}

Existing Operations (current project state):
${JSON.stringify(existingOperations)}

Transcript Data (use this to detect dead space, silences, and timings):
${JSON.stringify(transcript)}

If you decide no edits are needed, or if you cannot execute the task, you MUST call the finish_without_proposal tool and thoroughly explain WHY in the reason field. DO NOT use the ask_clarification tool.`;

    const contents = history.map(msg => ({ role: msg.role, content: msg.content }));

    const response = await generate({
      systemPolicy,
      contents,
      temperature: 0.2, // Slightly higher than 0.1 for conversational fluidity
      purpose: 'execution',
      tools: true,
    });

    const failure = response.toolCalls.find(tc => tc.name === 'finish_without_proposal');
    if (failure) {
      return { type: "edit_program", operations: [], summary: failure.args.reason };
    }

    if (!response.toolCalls || response.toolCalls.length === 0) {
      if (response.rawText) {
        return { type: "edit_program", operations: [], summary: response.rawText };
      }
      throw Object.assign(new Error("Agent did not return any operations or text response."), { code: 'worker_execution_error' });
    }

    const clarification = response.toolCalls.find(tc => tc.name === 'ask_clarification');
    if (clarification) {
      return { type: "clarification", questions: clarification.args.questions };
    }

    const validOperations = response.toolCalls.filter(tc => tc.name.startsWith('propose_') || !['ask_clarification', 'finalize_edit_program', 'finish_without_proposal', 'remove_draft_operation'].includes(tc.name));

    return {
      type: "edit_program",
      operations: await Promise.all(validOperations.map(async tc => {
        const operationName = tc.name.startsWith('propose_') ? tc.name.replace('propose_', '') : tc.name;
        let payload = tc.args.operation || tc.args;

        let target = undefined;
        if (payload.startMs !== undefined && payload.endMs !== undefined) {
          const defaultSourceId = payload.sourceId || (sources[0] && (sources[0].fingerprint || sources[0].id)) || 'default_source';
          target = { sourceId: defaultSourceId, startMs: payload.startMs, endMs: payload.endMs };
          const { sourceId, startMs, endMs, ...rest } = payload;
          payload = rest;
        }

        if (operationName === 'set_dialogue_loudness') {
          return { type: 'set_audio_levels', dialogueLufs: payload.targetLufs, reason: payload.reason, target };
        }
        if (operationName === 'set_music_ducking') {
          return { type: 'set_audio_levels', musicDuckDb: payload.duckDb, attackMs: payload.attackMs, releaseMs: payload.releaseMs, reason: payload.reason, target };
        }
        if (operationName === 'add_text_overlay') {
          try {
            const defaultSourceId = payload.sourceId || (sources[0] && (sources[0].fingerprint || sources[0].id)) || 'default_source';
            const source = sources.find(s => s.id === defaultSourceId || s.fingerprint === defaultSourceId) || sources[0];
            const videoWidth = source?.width || 1920;
            const videoHeight = source?.height || 1080;

            let finalDescription = payload.description || payload.text;
            if (payload.styling) {
              finalDescription += `\nStyling Instructions: ${payload.styling}`;
            }

            const blueprint = await generateTextOverlayConfig({ 
              description: finalDescription, 
              text: payload.text,
              videoWidth,
              videoHeight
            });
            return { type: operationName, target, ...payload, designBlueprint: JSON.stringify(blueprint) };
          } catch (e) {
            console.error("Failed to generate text overlay blueprint", e);
            return { type: operationName, target, ...payload, designBlueprint: "{}" };
          }
        }
        if (operationName === 'add_section_transition') {
          try {
            const blueprint = await generateTransitionConfig({ description: payload.description });
            return { type: operationName, target, ...payload, designBlueprint: JSON.stringify(blueprint) };
          } catch (e) {
            console.error("Failed to generate transition blueprint", e);
            return { type: operationName, target, ...payload, designBlueprint: "{}" };
          }
        }

        return { type: operationName, target, ...payload };
      })),
      summary: response.rawText || "Proposed edit"
    };
  };

  return { generateTextOverlayConfig, generateTransitionConfig, chatCompletion };
}

export { createOpenAIDecisionProvider };
