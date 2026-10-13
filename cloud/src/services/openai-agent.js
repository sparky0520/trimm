
import { openaiUsage } from '../schema/model-usage.js';
import { getAgentTools } from '../schema/agent-decision-schema.js';

import OpenAI from 'openai';

function generateCaptionNodes(videoNodes = [], transcriptWords = [], styleOptions = {}, wordsPerGroup = 4) {
  if (!Array.isArray(videoNodes) || videoNodes.length === 0 || !Array.isArray(transcriptWords) || transcriptWords.length === 0) {
    return [];
  }

  // 1. Map each transcript word to timeline coordinates if it falls inside an active video node
  const timelineWords = [];
  for (const word of transcriptWords) {
    const wStart = word.startMs ?? word.range?.startMs ?? 0;
    const wEnd = word.endMs ?? word.range?.endMs ?? wStart;
    const text = word.text ?? word.word ?? '';
    if (!text || !text.trim()) continue;

    for (const v of videoNodes) {
      const vSourceStart = v.sourceStartMs ?? 0;
      const vDuration = v.endMs - v.startMs;
      const vSourceEnd = vSourceStart + vDuration;

      // Check if word falls inside this video node
      if (wStart >= vSourceStart && wEnd <= vSourceEnd) {
        const tStart = v.startMs + (wStart - vSourceStart);
        const tEnd = v.startMs + (wEnd - vSourceStart);
        timelineWords.push({
          text: text.trim(),
          startMs: tStart,
          endMs: tEnd
        });
        break;
      }
    }
  }

  // 2. Group into caption text nodes
  const textNodes = [];
  let currentWords = [];
  let currentStart = 0;
  let currentEnd = 0;
  const groupSize = Math.max(1, wordsPerGroup || 4);

  for (let i = 0; i < timelineWords.length; i++) {
    const tw = timelineWords[i];
    if (currentWords.length === 0) {
      currentStart = tw.startMs;
    }
    currentWords.push(tw.text);
    currentEnd = tw.endMs;

    const nextWord = timelineWords[i + 1];
    const isPause = nextWord && (nextWord.startMs - currentEnd > 400);
    const isMaxWords = currentWords.length >= groupSize;
    const isLast = i === timelineWords.length - 1;

    if (isMaxWords || isPause || isLast) {
      textNodes.push({
        type: 'text',
        id: `caption_${Date.now()}_${i}_${Math.random().toString(36).substring(2, 7)}`,
        text: currentWords.join(' '),
        startMs: currentStart,
        endMs: currentEnd,
        style: {
          color: (styleOptions && styleOptions.color) || '#FFFFFF',
          fontSize: (styleOptions && styleOptions.fontSize) || 100,
          posY: (styleOptions && styleOptions.posY !== undefined) ? styleOptions.posY : -0.35,
          posX: (styleOptions && styleOptions.posX !== undefined) ? styleOptions.posX : 0,
          strokeColor: (styleOptions && styleOptions.strokeColor) || '#000000',
          strokeWidth: (styleOptions && styleOptions.strokeWidth !== undefined) ? styleOptions.strokeWidth : 4
        }
      });
      currentWords = [];
    }
  }

  return textNodes;
}

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
- transition (VideoNode): Applies a 500ms smooth native transition between clips. Supported values: "crossfade", "dip_to_black", "dip_to_white", "wipe", "slide".

Transcript Data (use this to detect dead space, silences, and timings):
${JSON.stringify(preprocessedTranscript)}

Use the finish_without_proposal tool ONLY AS A LAST RESORT if the request is fundamentally impossible. Always try to fulfill the user's creative intent using the available tools or the universal Video DSL before giving up. DO NOT use the ask_user_question tool unless absolutely necessary.

# Tool Usage Examples (Few-Shot Prompts):

1. User: "Cut out the silence at the beginning and the part where I messed up saying 'hello'."
-> call apply_schema_patch with the modified timeline where the VideoNode(s) skip those exact timestamps. (Do NOT generate captions unless the user specifically asks for captions or subtitles).

2. User: "Add captions to the video" / "Generate subtitles" / "Transcribe subtitles on screen"
-> call generate_captions to automatically generate synchronized captions based on the transcript onto the timeline.

3. User: "Remove captions" / "Delete subtitles" / "Hide captions"
-> call remove_captions to remove all caption/subtitle text nodes from the timeline.

4. User: "Put a comic book title on screen that says BAM! with a black outline."
-> call apply_schema_patch and add a TextNode with text "BAM!" and style: { strokeColor: "#000000", strokeWidth: 4 } in the timeline array.

5. User: "Change the color of the word 'hello' to yellow in the captions."
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
    const generateCaptionsCall = response.toolCalls.find(tc => tc.name === 'generate_captions');
    const removeCaptionsCall = response.toolCalls.find(tc => tc.name === 'remove_captions');

    if (generateCaptionsCall || removeCaptionsCall || schemaPatch) {
      let baseSchema = schemaPatch ? JSON.parse(JSON.stringify(schemaPatch.args.schema)) : (schema ? JSON.parse(JSON.stringify(schema)) : { settings: {}, timeline: [] });
      let summary = schemaPatch ? (schemaPatch.args.reason || response.rawText) : null;

      if (removeCaptionsCall) {
        baseSchema.timeline = (baseSchema.timeline || []).filter(node => node.type !== 'text');
        summary = removeCaptionsCall.args.reason || summary || "Removed captions from the video.";
      }

      if (generateCaptionsCall) {
        const videoNodes = (baseSchema.timeline || []).filter(node => node.type === 'video');
        const transcriptWords = (Array.isArray(transcript) && transcript.length > 0)
          ? transcript
          : (schema?.metadata?.transcripts || []);
        
        const newCaptionNodes = generateCaptionNodes(
          videoNodes,
          transcriptWords,
          generateCaptionsCall.args.style,
          generateCaptionsCall.args.wordsPerGroup
        );

        // Remove any prior caption text nodes and add newly generated ones
        const nonTextNodes = (baseSchema.timeline || []).filter(node => node.type !== 'text');
        baseSchema.timeline = [...nonTextNodes, ...newCaptionNodes];
        summary = generateCaptionsCall.args.reason || summary || "Generated synchronized captions for the video.";
      }

      // Enforce immutability of metadata
      if (schema && schema.metadata) {
        baseSchema.metadata = schema.metadata;
      }

      return {
        type: "schema_patch",
        schema: baseSchema,
        summary: summary || response.rawText || "Proposed edit"
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

export { createOpenAIDecisionProvider, generateCaptionNodes };
