
import { openaiUsage } from '../schema/model-usage.js';
import { getAgentTools } from '../schema/agent-decision-schema.js';

import OpenAI from 'openai';

function createOpenAIDecisionProvider({
  apiKey = process.env.LLM_API_KEY,
  baseURL = process.env.LLM_OPENAI_BASE_URL || 'https://api.moonshot.ai/v1',
  model = process.env.MODEL || 'kimi-k2.7-code',
} = {}) {

  const openai = new OpenAI({
    apiKey,
    baseURL,
  });

  async function generate({
    systemPolicy, contents, temperature, purpose = 'planning',
    responseFormat = null, tools = true, onStatus
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

    const toolsArray = typeof tools === 'boolean' && tools ? getAgentTools() : tools;

    const requestPayload = {
      model,
      messages,
      ...(responseFormat ? { response_format: responseFormat } : {}),
      ...(Array.isArray(toolsArray) && toolsArray.length > 0 ? { tools: toolsArray.map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters } })) } : {}),
      stream: true,
    };

    let stream;
    try {
      stream = await openai.chat.completions.create(requestPayload, { maxRetries: 4 });
    } catch (err) {
      console.error(`[DEBUG] OpenAI API Error:`, err);
      throw err;
    }

    let textContent = '';
    let toolCalls = [];
    let finishReason = null;
    let statusSent = false;

    for await (const chunk of stream) {
      const choice = chunk.choices[0];
      if (!choice) continue;
      if (choice.delta.content) textContent += choice.delta.content;
      if (choice.delta.tool_calls) {
        if (!statusSent && onStatus) {
          onStatus('editing');
          statusSent = true;
        }
        for (const tc of choice.delta.tool_calls) {
          if (!toolCalls[tc.index]) {
            toolCalls[tc.index] = { id: tc.id, type: 'function', function: { name: tc.function?.name || '', arguments: '' } };
          }
          if (tc.function?.name) toolCalls[tc.index].function.name = tc.function.name;
          if (tc.function?.arguments) toolCalls[tc.index].function.arguments += tc.function.arguments;
        }
      }
      if (choice.finish_reason) finishReason = choice.finish_reason;
    }

    if (!textContent && toolCalls.length === 0) {
      throw Object.assign(new Error('Model returned no decision.'), { status: 502, code: 'empty_provider_response' });
    }

    return {
      rawText: textContent || null,
      toolCalls: toolCalls.map(tc => ({ name: tc.function.name, args: JSON.parse(tc.function.arguments || '{}'), id: tc.id })),
      history: [...messages, { role: 'assistant', content: textContent, tool_calls: toolCalls }],
      finishReason: finishReason,
      usage: null,
    };
  }



  const chatCompletion = async ({ history = [], sources = [], transcript = [], schema = null }, onStatus) => {

    // Pre-process transcript for LLM visibility
    const preprocessedTranscript = [];
    let lastSpeakerId = null;
    let lastEndMs = 0;

    for (const word of transcript) {
      const startMs = word.startMs ?? word.range?.startMs ?? 0;
      const endMs = word.endMs ?? word.range?.endMs ?? startMs;

      // 1. Inject speaker change events
      if (word.speakerId && word.speakerId !== lastSpeakerId) {
        preprocessedTranscript.push({
          type: "speaker_change",
          newSpeaker: word.speakerId,
          timestampMs: startMs
        });
        lastSpeakerId = word.speakerId;
      }

      // 2. Inject explicit SILENCE blocks for gaps >= 400ms
      const gap = startMs - lastEndMs;
      if (lastEndMs > 0 && gap >= 400) {
        preprocessedTranscript.push({
          type: "silence",
          startMs: lastEndMs,
          endMs: startMs,
          durationMs: gap
        });
      }

      // 3. Pre-calculate durationMs on the word itself
      preprocessedTranscript.push({
        ...word,
        durationMs: endMs - startMs
      });

      lastEndMs = endMs;
    }

    const promptSchema = schema ? { ...schema } : null;
    if (promptSchema) delete promptSchema.metadata;

    const systemPolicy = `You are an AI video editor Agent.
Your job is to execute the user's editing requests by proposing operations using the appropriate tool(s).
You have access to the chat history, transcript, source media, and the current project state.

Available Sources:
${JSON.stringify(sources)}

Current Composition Schema (this is the timeline you must modify via apply_schema_patch):
${JSON.stringify(promptSchema, null, 2)}

Available Visual Effects (use these as the effectName in effect nodes):
- black_and_white
- vintage
- pixelate (supports intensity 0.0 to 1.0)
- zoom_blur (supports intensity 0.0 to 1.0)

Special Natively-Calculated Properties (USE THESE instead of calculating things manually):
- autoEnhance: true (CompositionSettings): Dynamically analyzes and boosts dialogue volume/compression on the device.
- autoDuck: true (AudioNode): Automatically fades out the main video track volume when the background audio plays.
- transition: "crossfade" (VideoNode): Applies a 500ms smooth crossfade/fade between the clips natively.

Transcript Data (use this to detect dead space, silences, and timings):
${JSON.stringify(preprocessedTranscript)}

Use the finish_without_proposal tool ONLY AS A LAST RESORT if the request is fundamentally impossible. Always try to fulfill the user's creative intent using the available tools or the universal Video DSL before giving up. DO NOT use the ask_user_question tool unless absolutely necessary.

# Tool Usage Examples (Few-Shot Prompts):

1. User: "Cut out the silence at the beginning and the part where I messed up saying 'hello'."
-> call apply_schema_patch with the modified timeline where the VideoNode(s) skip those exact timestamps.

2. User: "Put a comic book title on screen that says BAM! with a black outline."
-> call apply_schema_patch and add a TextNode with text "BAM!" and style: { strokeColor: "#000000", strokeWidth: 4 } in the timeline array.

3. User: "Change the color of the word 'hello' to yellow in the captions."
-> call apply_schema_patch and add a TextNode using the segments array (e.g. segments: [{text: "say "}, {text: "hello", color: "#FFFF00"}]).`;

    const contents = history.map(msg => ({ role: msg.role, content: msg.content }));

    const response = await generate({
      systemPolicy,
      contents,
      temperature: 0.2, // Slightly higher than 0.1 for conversational fluidity
      purpose: 'execution',
      tools: true,
      onStatus
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

    const clarification = response.toolCalls.find(tc => tc.name === 'ask_user_question');
    if (clarification) {
      return { type: "clarification", questions: clarification.args.questions };
    }

    const schemaPatch = response.toolCalls.find(tc => tc.name === 'apply_schema_patch');
    if (schemaPatch) {
      // Enforce immutability of metadata
      if (schema && schema.metadata) {
        schemaPatch.args.schema.metadata = schema.metadata;
      }
      return {
        type: "schema_patch",
        schema: schemaPatch.args.schema,
        summary: schemaPatch.args.reason || response.rawText || "Proposed edit"
      };
    }

    const validOperations = response.toolCalls.filter(tc => tc.name.startsWith('propose_') || !['ask_user_question', 'finalize_edit_program', 'finish_without_proposal', 'remove_draft_operation'].includes(tc.name));

    const removedOperations = response.toolCalls
      .filter(tc => tc.name === 'remove_draft_operation')
      .map(tc => tc.args.operationId);

    return {
      type: "edit_program",
      removedOperations,
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

        if (operationName === 'apply_video_dsl') {
          return { type: operationName, target, ...payload, dsl: typeof payload.dsl === 'string' ? payload.dsl : JSON.stringify(payload.dsl) };
        }

        if (payload.designBlueprint && typeof payload.designBlueprint !== 'string') {
          payload.designBlueprint = JSON.stringify(payload.designBlueprint);
        }

        return { type: operationName, target, ...payload };
      })),
      summary: response.rawText || "Proposed edit"
    };
  };

  return { chatCompletion };
}

export { createOpenAIDecisionProvider };
