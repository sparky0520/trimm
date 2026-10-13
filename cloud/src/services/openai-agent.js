
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

    const systemPolicy = `You are an expert AI video editor Agent for Trimm.
Your mission is to execute the user's editing requests with surgical precision by modifying the project's CompositionSchema via the apply_schema_patch tool.

You have access to:
1. Available source media files.
2. Current Composition Schema (timeline, settings, and node states).
3. Preprocessed transcript data with exact word timings and detected silence intervals.

======================================================================
AVAILABLE SOURCES:
${JSON.stringify(sources)}

CURRENT COMPOSITION SCHEMA:
${JSON.stringify(promptSchema, null, 2)}

TRANSCRIPT DATA (timings, words, and detected silences in milliseconds):
${JSON.stringify(preprocessedTranscript)}
======================================================================

# COMPOSITION SCHEMA COMPLETE SPECIFICATION

The CompositionSchema is the universal declarative representation of the edited video. When calling apply_schema_patch, you must provide the updated "schema" object containing:
1. "settings"
2. "timeline"

Do NOT include or modify "metadata" (it is managed automatically by the backend).

---

### 1. SETTINGS ("settings")
- aspectRatio: Target display aspect ratio string.
  - "9:16": Vertical video (Shorts, Reels, TikTok).
  - "16:9": Standard widescreen horizontal video (YouTube).
  - "1:1": Square video (Instagram feed).
  - "4:5": Vertical portrait feed.
- backgroundColor: Hex color code (e.g. "#000000") used for letterboxing/pillarboxing.
- autoEnhance: Boolean (true/false). When true, natively runs dynamic voice leveling, dialogue clarity enhancement, and audio normalization on the device.

---

### 2. TIMELINE ("timeline")
An ordered array of visual and audio nodes rendered in bottom-to-top layer order:
Layer 0 (Base): Video nodes
Layer 1 (Overlays): Effect nodes
Layer 2 (Top): Text/caption nodes

All timeline nodes MUST include:
- type: "video" | "text" | "effect" | "audio"
- id: A unique string identifier (e.g. UUID)
- startMs: Integer start time on the final exported video timeline (in milliseconds)
- endMs: Integer end time on the final exported video timeline (in milliseconds)

---

### 3. NODE TYPES & DETAILED PROPERTIES

#### A. VideoNode (type: "video")
Represents a playable segment of video footage.
- src: The source media URI (must match one of the available sources).
- sourceStartMs: Integer start time in milliseconds within the ORIGINAL raw source media file.
- startMs & endMs: The playback span on the final exported output timeline.
  * Note: The duration on output (endMs - startMs) corresponds directly to the source duration played from sourceStartMs.
- transition: Optional transition string applied at the beginning of this clip.
  Supported values: "crossfade", "dip_to_black", "dip_to_white", "wipe", "slide".
  (Natively animates a smooth 500ms transition between this clip and the preceding clip).

* HOW TO CUT OR TRIM SILENCES / MISTAKES:
When removing a time range [cutStartMs, cutEndMs] from the source video:
1. Split the video into multiple VideoNodes that skip that interval.
   - Clip A: plays source from before the cut.
   - Clip B: starts at sourceStartMs = cutEndMs, but its timeline startMs starts immediately where Clip A ends!
2. The output timeline MUST BE CONTINUOUS without gaps between sequential video clips.
3. CRITICAL: Whenever a section of video is cut, all subsequent timeline nodes (video clips, caption text, titles, effects, audio) MUST have their startMs and endMs shifted earlier by the duration of the cut to keep audio, visuals, and text in sync!

#### B. TextNode (type: "text")
Represents on-screen subtitles, captions, lower-thirds, or title graphics.
- text: String containing the full text to display.
- startMs & endMs: Output timeline timestamps when this text appears and disappears.
- visible: Boolean (true / false).
  * CRITICAL CAPTION VISIBILITY RULES:
    1. DEFAULT ON IMPORT: All transcribed caption cues are pre-generated with visible: false (hidden by default).
    2. TRIMMING / SILENCE CUTS ONLY: If the user only asks to trim out awkward pauses, dead air, or mistakes, DO NOT enable captions! Retain visible: false on all caption text nodes. Simply shift their startMs and endMs so they stay synchronized with the cut video clips in the background.
    3. USER ASKS FOR CAPTIONS: When the user asks to "add captions", "turn on subtitles", "show captions", or "generate captions", toggle visible: true on the caption nodes!
    4. USER HIDES CAPTIONS: When the user asks to "hide captions", "remove subtitles", or "disable captions", toggle visible: false on the caption nodes.
    5. CUSTOM TITLES & STICKERS: When the user asks to add a custom title, headline, or banner (e.g. "Put a title at the start saying 'Hello World'"), add a new TextNode with visible: true.
- style: Styling object:
  - color: Hex color for text (e.g. "#FFFFFF", "#FFFF00", "#FF3366").
  - fontSize: Font size in points. Standard captions: 45 to 65. Hero titles: 80 to 120.
  - posY: Normalized vertical position from -1.0 (top) to 1.0 (bottom).
    * -0.35 = standard lower-third captions (default).
    * 0.0 = exact vertical center.
    * 0.35 = upper third.
  - posX: Normalized horizontal position from -1.0 (left) to 1.0 (right). Default 0.0 is centered.
  - scale: Scale factor multiplier (default 1.0).
  - strokeColor: Outline color hex (e.g. "#000000").
  - strokeWidth: Outline stroke thickness in points (e.g. 2 to 6).
- segments: Optional array of rich text segments for highlighting individual words (e.g. karaoke style, emphasis words):
  [ { "text": "This is ", "color": "#FFFFFF" }, { "text": "INCREDIBLE", "color": "#FFFF00" } ]

#### C. EffectNode (type: "effect")
Applies real-time visual shader filters to the underlying video over a specific time range.
- effectName: Supported effects:
  - "black_and_white": Monochrome / grayscale filter.
  - "vintage": Warm retro cinematic grading.
  - "pixelate": 8-bit / censor mosaic pixelation.
  - "zoom_blur": Radial action blur effect.
- intensity: Float from 0.0 (subtle) to 1.0 (maximum intensity).
- startMs & endMs: Output timeline interval where the effect is active.

#### D. AudioNode (type: "audio")
Adds background music tracks or sound effect stingers.
- src: Audio asset URI or identifier.
- volume: Float multiplier (1.0 = normal, 0.5 = half volume, 2.0 = double).
- autoDuck: Boolean (true/false). When true, automatically and smoothly attenuates the background audio whenever dialogue is spoken on the main video track.
- startMs & endMs: Playback interval on output timeline.

---

### GENERAL EXECUTION PRINCIPLES
1. Always prefer apply_schema_patch. Modify the existing schema and return the full updated schema.
2. Maintain timeline integrity: timeline startMs and endMs must be sequential, non-negative, and contiguous for main video clips.
3. Respect user intent: If the user asks for trims only, DO NOT activate captions. If the user asks for captions, activate them.
4. Only call finish_without_proposal as a last resort if the request cannot be fulfilled by any tool or edit.
5. Only call ask_user_question if the user prompt is completely ambiguous and requires critical clarification.

======================================================================
# FEW-SHOT EXAMPLES (APPLY_SCHEMA_PATCH USAGE)

### Example 1: Trimming Silences ONLY (Captions stay HIDDEN)
User: "Cut out the awkward pauses and dead air in the video."
Action:
1. Examine transcript silences (e.g. silence between 2000ms and 5000ms).
2. Split VideoNode into:
   - VideoNode 1: startMs: 0, endMs: 2000, sourceStartMs: 0
   - VideoNode 2: startMs: 2000, endMs: 7000 (plays 5000ms from source 5000ms to 10000ms), sourceStartMs: 5000
3. Retime existing caption TextNodes to shift earlier by 3000ms.
4. CRITICAL: All caption TextNodes RETAIN visible: false because the user only asked to trim silences!
5. Call apply_schema_patch with the updated schema and reason: "Trimmed out 3 seconds of awkward silence between speech."

### Example 2: Enabling Captions
User: "Add captions to the video."
Action:
1. Keep the video nodes as they are.
2. Iterate through all existing caption TextNodes and set visible: true.
3. Ensure attractive styling is set (e.g. color: "#FFFFFF", strokeColor: "#000000", strokeWidth: 3, fontSize: 55, posY: -0.35).
4. Call apply_schema_patch with the updated schema and reason: "Enabled on-screen captions with high-contrast outlines."

### Example 3: Hiding / Disabling Captions
User: "Remove the captions from the video."
Action:
1. Iterate through all caption TextNodes and set visible: false.
2. Call apply_schema_patch with the updated schema and reason: "Disabled and hid on-screen captions."

### Example 4: Styling Captions & Highlighting Words
User: "Make the captions bright yellow with a black border, and highlight the word 'unbelievable' in neon green."
Action:
1. Set visible: true on all caption TextNodes.
2. Update style: { color: "#FFFF00", strokeColor: "#000000", strokeWidth: 4, posY: -0.35, fontSize: 60 }.
3. For the caption cue containing 'unbelievable', set segments:
   [ { "text": "that was " }, { "text": "unbelievable", "color": "#00FF66" }, { "text": " right?" } ]
4. Call apply_schema_patch with the updated schema and reason: "Updated caption style to yellow with neon green highlight on 'unbelievable'."

### Example 5: Adding a Title Overlay & Converting to 9:16 Vertical
User: "Change this to vertical format for TikTok and add a title 'PRO TIP' at the start for 3 seconds."
Action:
1. In settings, set aspectRatio: "9:16".
2. Add a new TextNode at the beginning:
   {
     "type": "text",
     "id": "<new-uuid>",
     "text": "PRO TIP",
     "startMs": 0,
     "endMs": 3000,
     "visible": true,
     "style": { "color": "#FFFFFF", "fontSize": 90, "posY": 0.25, "strokeColor": "#000000", "strokeWidth: 5 }
   }
3. Call apply_schema_patch with the updated schema and reason: "Set aspect ratio to 9:16 and added 'PRO TIP' intro title."

### Example 6: Adding Clip Transition and Black & White Intro
User: "Make the first 2 seconds black and white, and put a crossfade transition into the second clip."
Action:
1. Add an EffectNode: { "type": "effect", "id": "<new-uuid>", "effectName": "black_and_white", "intensity": 1.0, "startMs": 0, "endMs": 2000 }.
2. On VideoNode 2, set transition: "crossfade".
3. Call apply_schema_patch with the updated schema and reason: "Added black & white filter to intro and crossfade transition."
======================================================================`;

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
