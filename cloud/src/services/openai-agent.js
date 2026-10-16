
import { openaiUsage } from '../schema/model-usage.js';
import { getAgentTools } from '../schema/agent-decision-schema.js';
import OpenAI from 'openai';

function mergeSchemaPatch(baseSchema, patchSchema, sources = [], removedNodeIds = []) {
  if (!baseSchema && !patchSchema) return null;
  const defaultSrc = sources[0]?.uri || '';

  const removalSet = new Set([
    ...(Array.isArray(removedNodeIds) ? removedNodeIds : []),
    ...((patchSchema?.timeline || []).filter(n => n && (n.deleted === true || n.remove === true)).map(n => n.id))
  ]);

  if (!baseSchema) {
    const s = patchSchema ? JSON.parse(JSON.stringify(patchSchema)) : {};
    if (Array.isArray(s.timeline)) {
      s.timeline = s.timeline
        .filter(n => !removalSet.has(n.id))
        .map(n => {
          if ((n.type === 'video' || n.type === 'audio') && !n.src) {
            return { ...n, src: defaultSrc };
          }
          return n;
        });
    }
    return s;
  }
  if (!patchSchema) {
    if (removalSet.size > 0 && Array.isArray(baseSchema.timeline)) {
      return {
        ...baseSchema,
        timeline: baseSchema.timeline.filter(n => !removalSet.has(n.id))
      };
    }
    return baseSchema;
  }

  const settings = {
    aspectRatio: "9:16",
    backgroundColor: "#000000",
    autoEnhance: false,
    ...(baseSchema.settings || {}),
    ...(patchSchema.settings || {})
  };

  const prevTimeline = (Array.isArray(baseSchema.timeline) ? baseSchema.timeline : [])
    .filter(n => !removalSet.has(n.id));
  const patchTimeline = (Array.isArray(patchSchema.timeline) ? patchSchema.timeline : [])
    .filter(n => !removalSet.has(n.id) && !n.deleted && !n.remove);

  if (patchTimeline.length === 0) {
    return {
      settings,
      timeline: prevTimeline,
      metadata: baseSchema.metadata || {}
    };
  }

  const patchHasVideo = patchTimeline.some(n => n.type === 'video');
  const prevVideoNodes = prevTimeline.filter(n => n.type === 'video');

  const prevNodeMap = new Map();
  prevTimeline.forEach((n, idx) => {
    if (n.id) prevNodeMap.set(n.id, n);
    prevNodeMap.set(`${n.type}_${idx}`, n);
  });

  const mergedNodes = [];

  // If patch did not provide any video nodes (e.g. user only edited captions/titles),
  // retain existing video nodes!
  if (!patchHasVideo && prevVideoNodes.length > 0) {
    mergedNodes.push(...prevVideoNodes);
  }

  // Process patch nodes
  for (let idx = 0; idx < patchTimeline.length; idx++) {
    const pNode = patchTimeline[idx];
    const prevNode = prevNodeMap.get(pNode.id) || prevNodeMap.get(`${pNode.type}_${idx}`) || {};

    const node = {
      ...prevNode,
      ...pNode,
    };

    if (node.type === 'video' || node.type === 'audio') {
      if (!node.src) {
        node.src = prevNode.src || defaultSrc;
      }
    }

    if (pNode.transition === null || pNode.transition === 'none' || pNode.transition === '' || pNode.transition === false) {
      node.transition = null;
    }

    // Deep style merging and property removal/clearing
    if (node.type === 'text') {
      if (prevNode.style || pNode.style) {
        const prevStyle = prevNode.style || {};
        const patchStyle = pNode.style || {};
        const mergedStyle = { ...prevStyle, ...patchStyle };

        // Remove style properties where patch explicitly passed null, undefined, or empty string
        for (const key of Object.keys(patchStyle)) {
          if (patchStyle[key] === null || patchStyle[key] === undefined || patchStyle[key] === '') {
            delete mergedStyle[key];
          }
        }
        // If strokeWidth is explicitly 0, clean up strokeColor if not specified
        if (patchStyle.strokeWidth === 0 && !patchStyle.strokeColor) {
          delete mergedStyle.strokeColor;
          delete mergedStyle.strokeWidth;
        }

        node.style = Object.keys(mergedStyle).length > 0 ? mergedStyle : null;
      }
    }

    mergedNodes.push(node);
  }

  // Preserve other untouched nodes (e.g. effect or audio or text if not replaced and not removed)
  const patchIds = new Set(patchTimeline.map(n => n.id).filter(Boolean));
  const untouchedNodes = prevTimeline.filter(n => {
    if (n.type === 'video' && patchHasVideo) return false;
    if (n.id && patchIds.has(n.id)) return false;
    return true;
  });

  const combined = [];
  const seenIds = new Set();
  for (const n of [...mergedNodes, ...untouchedNodes]) {
    const key = n.id || `${n.type}_${n.startMs}_${n.endMs}`;
    if (!seenIds.has(key)) {
      seenIds.add(key);
      combined.push(n);
    }
  }

  const layerOrder = { video: 0, effect: 1, audio: 2, text: 3 };
  combined.sort((a, b) => {
    const layerA = layerOrder[a.type] ?? 4;
    const layerB = layerOrder[b.type] ?? 4;
    if (layerA !== layerB) return layerA - layerB;
    return (a.startMs || 0) - (b.startMs || 0);
  });

  return {
    settings,
    timeline: combined,
    metadata: baseSchema.metadata || patchSchema.metadata || {}
  };
}

function sliceVideoNodesBySilences(baseSchema, silences, sourceUri, originalDurationMs, minSilenceMs = 500) {
  if (!baseSchema || !Array.isArray(silences) || silences.length === 0 || !originalDurationMs) {
    return baseSchema;
  }
  const filteredSilences = silences
    .filter(s => (s.endMs - s.startMs) >= minSilenceMs)
    .sort((a, b) => a.startMs - b.startMs);

  if (filteredSilences.length === 0) return baseSchema;

  const retained = [];
  let curr = 0;
  for (const s of filteredSilences) {
    if (s.startMs > curr) {
      retained.push({ sourceStartMs: curr, sourceEndMs: s.startMs });
    }
    curr = Math.max(curr, s.endMs);
  }
  if (curr < originalDurationMs) {
    retained.push({ sourceStartMs: curr, sourceEndMs: originalDurationMs });
  }

  if (retained.length === 0) return baseSchema;

  let timelineMs = 0;
  const newVideoNodes = retained.map((r, i) => {
    const dur = r.sourceEndMs - r.sourceStartMs;
    const node = {
      type: 'video',
      id: `vseg-${i}-${Date.now().toString(36)}`,
      src: sourceUri,
      startMs: timelineMs,
      endMs: timelineMs + dur,
      sourceStartMs: r.sourceStartMs,
      transition: null
    };
    timelineMs += dur;
    return node;
  });

  const sourceToOutput = (sourceMs) => {
    let outputMs = 0;
    for (const r of retained) {
      const dur = r.sourceEndMs - r.sourceStartMs;
      if (sourceMs < r.sourceStartMs) return outputMs;
      if (sourceMs <= r.sourceEndMs) return outputMs + (sourceMs - r.sourceStartMs);
      outputMs += dur;
    }
    return outputMs;
  };

  const otherNodes = (baseSchema.timeline || [])
    .filter(n => n.type !== 'video')
    .map(n => {
      const newStart = sourceToOutput(n.startMs);
      const newEnd = sourceToOutput(n.endMs);
      return {
        ...n,
        startMs: newStart,
        endMs: Math.max(newStart, newEnd)
      };
    });

  return {
    ...baseSchema,
    timeline: [...newVideoNodes, ...otherNodes]
  };
}

function createOpenAIDecisionProvider({
  apiKey = process.env.LLM_API_KEY,
  baseURL = process.env.LLM_OPENAI_BASE_URL || 'https://api.moonshot.ai/v1',
  model = process.env.MODEL || 'kimi-k2.7-code',
  openaiClient = null,
} = {}) {

  const openai = openaiClient || new OpenAI({
    apiKey,
    baseURL,
  });

  async function generate({
    systemPolicy, contents, temperature, purpose = 'planning',
    responseFormat = null, tools = true, onStatus
  }) {
    if (!apiKey && !openaiClient) throw Object.assign(new Error('API key is not configured'), { status: 503, code: 'provider_unconfigured' });

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

    // Speech analytics and human-readable narrative reconstruction
    const spokenWordsList = transcript.map(w => (w.text || '').trim()).filter(Boolean);
    const fullSpokenText = spokenWordsList.join(' ');

    const readableTranscriptChunks = [];
    let currentChunkWords = [];
    let chunkStartMs = null;
    let chunkEndMs = null;

    for (const item of preprocessedTranscript) {
      if (item.type === 'silence' || item.type === 'speaker_change') {
        if (currentChunkWords.length > 0) {
          const startSec = (chunkStartMs / 1000).toFixed(1);
          const endSec = (chunkEndMs / 1000).toFixed(1);
          readableTranscriptChunks.push(`[${startSec}s - ${endSec}s]: "${currentChunkWords.join(' ')}"`);
          currentChunkWords = [];
          chunkStartMs = null;
        }
        if (item.type === 'silence' && item.durationMs >= 800) {
          readableTranscriptChunks.push(`   *(Dead air / awkward pause: ${(item.durationMs / 1000).toFixed(1)}s)*`);
        }
      } else if (item.text) {
        if (chunkStartMs === null) chunkStartMs = item.startMs ?? 0;
        chunkEndMs = item.endMs ?? chunkStartMs;
        currentChunkWords.push(item.text);
        if (currentChunkWords.length >= 18 || (item.text.match(/[.?!]$/) && currentChunkWords.length >= 8)) {
          const startSec = (chunkStartMs / 1000).toFixed(1);
          const endSec = (chunkEndMs / 1000).toFixed(1);
          readableTranscriptChunks.push(`[${startSec}s - ${endSec}s]: "${currentChunkWords.join(' ')}"`);
          currentChunkWords = [];
          chunkStartMs = null;
        }
      }
    }
    if (currentChunkWords.length > 0) {
      const startSec = (chunkStartMs / 1000).toFixed(1);
      const endSec = (chunkEndMs / 1000).toFixed(1);
      readableTranscriptChunks.push(`[${startSec}s - ${endSec}s]: "${currentChunkWords.join(' ')}"`);
    }

    const totalDurationMs = sources[0]?.durationMs || lastEndMs || 0;
    const totalDurationSec = (totalDurationMs / 1000).toFixed(1);
    const detectedSilences = preprocessedTranscript.filter(t => t.type === 'silence');
    const totalSilenceMs = detectedSilences.reduce((acc, s) => acc + (s.durationMs || 0), 0);
    const speechTimeMinutes = Math.max(0.1, (totalDurationMs - totalSilenceMs) / 60000);
    const wordsPerMinute = Math.round(spokenWordsList.length / speechTimeMinutes);

    const promptSchema = schema ? { ...schema } : null;
    if (promptSchema) delete promptSchema.metadata;

    const systemPolicy = `You are an expert AI Video Editor and Creative Director / Script Doctor for Trimm.

You operate across two integrated roles:
1. SCRIPT DOCTOR & CONTENT CONSULTANT (Direct Markdown Response):
   Analyze the creator's rough video recording, diagnose hook/pacing/structure strengths and weaknesses, suggest high-retention script improvements, and deliver a polished, structured script ready for performance or re-recording.
2. PRECISION VIDEO EDITOR (apply_schema_patch Tool):
   Execute timeline modifications with surgical precision (cutting dead air/silences, trimming clips, adding/styling captions, applying visual shaders, adjusting aspect ratio) by modifying the project's CompositionSchema.


# SCRIPT DOCTOR & VIDEO ANALYSIS MASTER SPECIFICATION

### CREATOR CONTEXT: THE "ROUGH FIRST RECORD"
Creators use Trimm to record raw, rough first takes as an exploratory brain-dump or scratchpad of what they want to say.
A rough first record typically exhibits:
- Fumbles, "ums", "likes", "so basically", false starts, and repeated sentences.
- Slow or meandering openings (e.g. "Hey guys, welcome back, today I want to talk about...").
- Points blurted out of logical order as they came to mind.
- Fluff, tangents, and over-explaining that cause viewer drop-off.
- Abrupt, weak, or missing Call to Action (CTA).

### CRITICAL ANTI-PATTERN: NEVER REGURGITATE THE RAW TRANSCRIPT
When the user asks to:
- "Analyse my video and suggest me improvements in the script"
- "Check the transcript and give me a structured script based on what I said in the video"
- "Give me a structured script from my rough first record"
- "Help me rewrite or polish what I said"
- "Review my script / video and tell me how to make it better"

NEVER just repeat the raw transcript back to the user with a few headings or timestamps.
NEVER just group their raw, rambling spoken sentences into bullet points.
Parroting the rough take verbatim is COMPLETELY UNACCEPTABLE.

Instead, treat the rough recording as the RAW SEED OF IDEAS AND INTENT:
1. Extract what the creator was TRYING to say (the core value, key insights, and audience takeaway).
2. Cut all the fluff, hesitations, repetitive phrases, and dead air.
3. Structure and rewrite it into an elevated, high-retention, punchy script with clear sections.
4. Provide an honest, insightful critique with concrete suggestions to make the video hook viewers and hold attention.

### SCRIPT CONSULTATION RESPONSE STRUCTURE
When asked for video/script analysis or a structured script, reply DIRECTLY with rich, beautifully structured Markdown using the following framework:

1. ### 🎯 Video & Narrative Analysis
- **Core Intent & Message**: The central thesis and the value promised to the viewer.
- **Hook & First 3 Seconds**: Critique of the opening. Is it slow, rambling, or buried? Why does it risk viewer drop-off, and how can it be sharpened?
- **Pacing & Fluff Diagnosis**: Specific moments where the video loses momentum (e.g. awkward pauses, tangents, repetitive points, filler words).
- **Delivery & Cadence**: Observations on speaking pace (${wordsPerMinute} WPM), energy, and clarity.

2. ### 💡 Key Script Improvement Recommendations
- **Hook Strategy**: 1-2 thumb-stopping hook options (e.g. Problem-Agitate, Counter-Intuitive Claim, Story Teaser).
- **Narrative Arc**: How to logically flow the ideas (Hook -> Tension/Context -> Core Value/Numbered Steps -> Climax/Payoff -> Outro/CTA).
- **Fluff to Cut**: Specific lines, tangents, or redundancies from the rough take that should be removed.

3. ### 📝 Structured & Polished Script (Ready to Deliver)
A newly rewritten, elevated script based on the creator's ideas (NOT their raw words). Format with clear section markers:
- **[01. Hook (0:00 - 0:03)]** — Magnetic opening line that grabs attention in under 3 seconds.
- **[02. The Problem / Setup]** — Relatable tension or context that makes the viewer care.
- **[03. Core Value / The Breakdown]** — Crisp, punchy points or steps delivering on the hook without fluff.
- **[04. The Climax / Big Takeaway]** — The memorable payoff or transformation.
- **[05. Call to Action (CTA)]** — Clear, natural next step (e.g., comment, follow, try this tip).
*Include delivery notes and visual/b-roll cues in brackets (e.g. [Energetic, look into camera], [Text on screen: "SECRET 1"], [Quick cut], [Pause for emphasis]).*

4. ### 🔄 Before vs. After (Side-by-Side Polish)
Show 2-3 side-by-side examples comparing what was said in the rough take with the polished script:
- **Rough Take (What you said)**: "..."
- **Polished Line (Recommended)**: "..."
- **Why it's better**: Explanation of why the new version is more punchy, concise, or engaging.

5. ### ✂️ Next Steps in Trimm
Give actionable guidance on how to proceed:
- **Option A (Edit Current Footage in Trimm)**: Explain what cuts or trims can be made right now to tighten the existing video (e.g. "I can cut the dead air at 0:12 and remove the repeated take at 0:28 right now! Just reply 'Cut pauses and mistakes'!").
- **Option B (Fast 2nd Take)**: Recommend doing a quick, punchy re-record using the polished script for maximum quality and flow.

---

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
  * DEFAULT: null.
  * CRITICAL TRANSITION RULE: NEVER add transitions between clips unless the user EXPLICITLY asks for them (e.g. "add a crossfade", "use dip to black", "put transitions between clips"). For standard cuts, silence removals, trims, and clip rearrangements, transition MUST ALWAYS be null (or omitted). Hard cuts without transitions are the standard in professional video editing.

* HOW TO CUT OR TRIM SILENCES / MISTAKES:
When removing a time range [cutStartMs, cutEndMs] from the source video:
1. Split the video into multiple VideoNodes that skip that interval.
   - Clip A: plays source from before the cut.
   - Clip B: starts at sourceStartMs = cutEndMs, but its timeline startMs starts immediately where Clip A ends!
2. The output timeline MUST BE CONTINUOUS without gaps between sequential video clips.
3. CRITICAL: Whenever a section of video is cut, all subsequent timeline nodes (video clips, caption text, titles, effects, audio) MUST have their startMs and endMs shifted earlier by the duration of the cut to keep audio, visuals, and text in sync!
4. CRITICAL RULE: When removing dead air, awkward pauses, or silences, you MUST return MULTIPLE sequential VideoNode items that omit the silence gaps. NEVER return a single uncut VideoNode spanning the whole video when claiming to cut pauses or dead air.


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
  - posY: Normalized vertical position from -1.0 (bottom of screen) to 1.0 (top of screen).
    * -0.35 = standard lower-third captions (default).
    * 0.0 = exact vertical center.
    * 0.35 = upper third (title / header area).
    * INCREASING posY moves text UP (towards the top of the video).
    * DECREASING posY moves text DOWN (towards the bottom of the video).
  - posX: Normalized horizontal position from -1.0 (left edge) to 1.0 (right edge). Default 0.0 is centered.
    * INCREASING posX moves text RIGHT.
    * DECREASING posX moves text LEFT.
  - scale: Scale factor multiplier (default 1.0).
    * INCREASING scale (e.g. 1.3) enlarges/zooms text.
    * DECREASING scale (e.g. 0.8) shrinks text.
  - strokeColor: Outline color hex (e.g. "#000000").
  - strokeWidth: Outline stroke thickness in points (e.g. 0 = none, 2-4 = standard, 6-8 = heavy).
    * INCREASING strokeWidth makes the outline border thicker and punchier.
    * DECREASING strokeWidth makes the outline border thinner or removes it (0).
- segments: Optional array of rich text segments for highlighting individual words (e.g. karaoke style, emphasis words):
  [ { "text": "This is ", "color": "#FFFFFF" }, { "text": "INCREDIBLE", "color": "#FFFF00" } ]

#### C. EffectNode (type: "effect")
Applies real-time visual shader filters to the underlying video over a specific time range.
- effectName: Supported effects:
  - "black_and_white": Monochrome / grayscale filter.
  - "vintage": Warm retro cinematic grading.
  - "pixelate": 8-bit / censor mosaic pixelation.
  - "zoom_blur": Radial action blur effect.
- intensity: Float from 0.0 (off/none) to 1.0 (maximum intensity).
  * INCREASING intensity makes the filter stronger and more pronounced.
  * DECREASING intensity softens the filter so the raw footage shows through more gently.
- startMs & endMs: Output timeline interval where the effect is active.

#### D. AudioNode (type: "audio")
Adds background music tracks or sound effect stingers.
- src: Audio asset URI or identifier.
- volume: Float multiplier (1.0 = standard 100%, 0.5 = half volume, 0.2 = quiet background, 0.0 = muted, 1.5 = boosted).
  * INCREASING volume makes the audio louder.
  * DECREASING volume makes the audio quieter.
- autoDuck: Boolean (true/false). When true, automatically and smoothly attenuates the background audio whenever dialogue is spoken on the main video track.
- startMs & endMs: Playback interval on output timeline.

---

### # NUMERIC PARAMETERS CHEAT SHEET (WHAT HAPPENS ON INCREASING VS DECREASING)

Use this reference table whenever modifying any numeric value in the schema:

| Parameter | Type / Node | Typical Range | What happens on INCREASING | What happens on DECREASING |
|---|---|---|---|---|
| startMs | Integer / All | 0 to timeline duration | Moves element LATER on the exported timeline. | Moves element EARLIER on the exported timeline. |
| endMs | Integer / All | > startMs | Extends duration (element stays on screen LONGER). | Shortens duration (cuts element off SOONER). |
| sourceStartMs | Integer / Video & Audio | 0 to raw file duration | TRIMS / SKIPS the start of raw media (starts later in raw clip). | REVEALS earlier raw footage from the beginning of the file. |
| posY | Float / Text style | -1.0 to 1.0 (default -0.35) | Moves text UP towards the top of the screen (e.g. -0.35 -> 0.0 center -> 0.35 top). | Moves text DOWN towards the bottom of the screen (e.g. -0.35 -> -0.5 lower). |
| posX | Float / Text style | -1.0 to 1.0 (default 0.0) | Moves text RIGHT towards the right edge. | Moves text LEFT towards the left edge. |
| fontSize | Number / Text style | 40 to 120 (default ~55) | Makes font LARGER and more readable from distance. | Makes font SMALLER and more compact. |
| scale | Float / Text style | 0.5 to 2.5 (default 1.0) | Magnifies / scales up entire graphic node (e.g. 1.5 = 150%). | Shrinks graphic node (e.g. 0.8 = 80%). |
| strokeWidth | Number / Text style | 0 to 10 (default 3) | THICKENS the outline border (increases legibility on bright backgrounds). | THINS or removes the outline border (0 = no outline). |
| intensity | Float / Effect | 0.0 to 1.0 (default 1.0) | STRENGTHENS shader filter (e.g. heavier blur, more pixelated, pure B&W). | SOFTENS shader filter (subtler effect, closer to original). |
| volume | Float / Audio | 0.0 to 2.0 (default 1.0) | LOUDENS audio track (e.g. 1.3 = 30% louder). | QUIETS audio track (e.g. 0.25 for background music, 0.0 for mute). |

---

### GENERAL EXECUTION PRINCIPLES
1. Request Routing:
   - TIMELINE EDIT REQUESTS (cutting silences/mistakes, trimming, captions, visual effects, aspect ratio, audio levels):
     Always call apply_schema_patch. Modify the existing schema and return the patch with a concise reason.
   - SCRIPT DOCTOR / SCRIPT IMPROVEMENT / VIDEO ANALYSIS REQUESTS (analyzing speech, suggesting improvements in the script, structuring rough takes into polished scripts):
     Respond DIRECTLY in rich Markdown text. DO NOT call apply_schema_patch (unless the user explicitly asked to cut/edit the video clips in the same request). DO NOT call finish_without_proposal.
   - HYBRID REQUESTS (e.g. "Cut out the silences and also suggest script improvements"):
     Call apply_schema_patch to execute the timeline edits, AND include the comprehensive script analysis, suggestions, and polished script in the reason parameter and text response!
2. TOKEN-SAVING PARTIAL PATCHING (DELTA UPDATES):
   - You DO NOT need to rewrite unchanged video clips or full timelines! To save tokens and avoid dropping fields:
   - When modifying or styling captions only: Return ONLY the text nodes being styled or toggled. Unchanged video clips, audio tracks, and effects are automatically preserved from the previous schema.
   - When cutting video only: Return the cut video nodes. Unchanged captions and tracks will be retained.
   - Any omitted fields on existing nodes (such as video 'src' or 'sourceStartMs') are automatically preserved.
3. STRICT NEGATIVE CONSTRAINTS:
   - NO UNREQUESTED TRANSITIONS: NEVER add 'transition: "crossfade"' or any transition unless the user explicitly asked for them using words like "transition" or "crossfade". Hard cuts with null transition are standard.
   - NO FAKE CUTS IN TEXT: When the user asks to cut silences or trim video, you MUST invoke 'apply_schema_patch'. Never claim in plain text that you cut or trimmed the video without calling the tool!
4. Maintain timeline integrity: timeline startMs and endMs must be sequential, non-negative, and contiguous for main video clips.
5. Respect user intent: If the user asks for trims only, DO NOT activate captions. If the user asks for captions, activate them.
6. Only call finish_without_proposal as a last resort if the request cannot be fulfilled by any tool or edit.
7. Only call ask_user_question if the user prompt is completely ambiguous and requires clarification before any action can be taken.
======================================================================
# FEW-SHOT EXAMPLES (USAGE PATTERNS WITH EXACT JSON TOOL CALLS)

### Example 1: Trimming Silences ONLY (Captions stay HIDDEN)
User: "Cut out the awkward pauses and dead air in the video."
Action:
1. Examine transcript silences (e.g. silence between 2000ms and 5000ms).
2. Split VideoNode into clips omitting the silence gap.
3. CRITICAL: All caption TextNodes RETAIN visible: false because the user only asked to trim silences!
Tool Call:
apply_schema_patch({
  "reason": "Trimmed out 3 seconds of awkward silence between speech.",
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      { "type": "video", "id": "v1", "src": "content://media/1.mp4", "startMs": 0, "endMs": 2000, "sourceStartMs": 0, "transition": null },
      { "type": "video", "id": "v2", "src": "content://media/1.mp4", "startMs": 2000, "endMs": 7000, "sourceStartMs": 5000, "transition": null }
    ]
  }
})

### Example 2: Enabling Captions
User: "Add captions to the video."
Action:
1. Keep the video nodes as they are.
2. Iterate through existing caption TextNodes and set visible: true with high-contrast outlines.
Tool Call:
apply_schema_patch({
  "reason": "Enabled on-screen captions with high-contrast outlines.",
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      {
        "type": "text",
        "id": "caption-1",
        "startMs": 100,
        "endMs": 1800,
        "text": "Welcome to my quick video",
        "visible": true,
        "style": { "color": "#FFFFFF", "strokeColor": "#000000", "strokeWidth": 3, "fontSize": 55, "posY": -0.35 }
      }
    ]
  }
})

### Example 3: Hiding / Disabling Captions
User: "Remove the captions from the video."
Action:
1. Set visible: false on all caption TextNodes.
Tool Call:
apply_schema_patch({
  "reason": "Disabled and hid on-screen captions.",
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      { "type": "text", "id": "caption-1", "startMs": 100, "endMs": 1800, "visible": false }
    ]
  }
})

### Example 4: Styling Captions & Highlighting Words
User: "Make the captions bright yellow with a black border, and highlight the word 'unbelievable' in neon green."
Action:
1. Set visible: true on caption nodes.
2. Set yellow color, black stroke, and use segments to highlight the specific word.
Tool Call:
apply_schema_patch({
  "reason": "Updated caption style to yellow with neon green highlight on 'unbelievable'.",
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      {
        "type": "text",
        "id": "caption-1",
        "startMs": 500,
        "endMs": 2500,
        "text": "that was unbelievable right?",
        "visible": true,
        "style": { "color": "#FFFF00", "strokeColor": "#000000", "strokeWidth": 4, "posY": -0.35, "fontSize": 60 },
        "segments": [
          { "text": "that was " },
          { "text": "unbelievable", "color": "#00FF66" },
          { "text": " right?" }
        ]
      }
    ]
  }
})

### Example 5: Adding a Title Overlay & Converting to 9:16 Vertical
User: "Change this to vertical format for TikTok and add a title 'PRO TIP' at the start for 3 seconds."
Action:
1. In settings, set aspectRatio: "9:16".
2. Add a new TextNode at the beginning.
Tool Call:
apply_schema_patch({
  "reason": "Set aspect ratio to 9:16 and added 'PRO TIP' intro title overlay.",
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      {
        "type": "text",
        "id": "title-intro-1",
        "text": "PRO TIP",
        "startMs": 0,
        "endMs": 3000,
        "visible": true,
        "style": { "color": "#FFFFFF", "fontSize": 90, "posY": 0.25, "strokeColor": "#000000", "strokeWidth": 5 }
      }
    ]
  }
})

### Example 6: Adding Clip Transition and Black & White Intro
User: "Make the first 2 seconds black and white, and put a crossfade transition into the second clip."
Action:
1. Add an EffectNode for black_and_white over [0, 2000].
2. On VideoNode 2, set transition: "crossfade".
Tool Call:
apply_schema_patch({
  "reason": "Added black & white shader to intro and crossfade transition to second clip.",
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      { "type": "effect", "id": "eff-bw-1", "effectName": "black_and_white", "intensity": 1.0, "startMs": 0, "endMs": 2000 },
      { "type": "video", "id": "v2", "startMs": 2000, "endMs": 6000, "sourceStartMs": 3000, "transition": "crossfade" }
    ]
  }
})

### Example 7: Moving Captions Higher & Making Text Bigger (Adjusting posY & fontSize)
User: "The captions are covering the product at the bottom, move them higher up and make the text bigger and easier to read."
Action:
1. Adjust posY from -0.35 to 0.1 (higher up).
2. Increase fontSize from 55 to 75.
Tool Call:
apply_schema_patch({
  "reason": "Moved captions higher up (posY: 0.1) and increased font size to 75 with bolder outline.",
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      { "type": "text", "id": "caption-1", "startMs": 0, "endMs": 2000, "visible": true, "style": { "posY": 0.1, "fontSize": 75, "strokeWidth": 4 } }
    ]
  }
})

### Example 8: Trimming The Beginning of Raw Video (Increasing sourceStartMs)
User: "Cut off the first 2 seconds of the video, it was just dead air before I started talking."
Action:
1. Set sourceStartMs: 2000 on VideoNode 1 (skipping the first 2000ms of raw media).
2. Retime duration to 6000ms (startMs: 0, endMs: 6000).
Tool Call:
apply_schema_patch({
  "reason": "Trimmed 2 seconds from the start by setting sourceStartMs to 2000 and shifting timeline.",
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      { "type": "video", "id": "v1", "startMs": 0, "endMs": 6000, "sourceStartMs": 2000, "transition": null }
    ]
  }
})

### Example 9: Adjusting Audio Volume & Softening Filter Intensity
User: "The background music is way too loud, turn it down so you can hear my voice, and make the vintage filter subtle."
Action:
1. Set volume: 0.25 on background audio node.
2. Set intensity: 0.35 on vintage effect node.
Tool Call:
apply_schema_patch({
  "reason": "Lowered background music volume to 0.25 and softened vintage filter intensity to 0.35.",
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      { "type": "audio", "id": "bgm-1", "volume": 0.25, "autoDuck": true, "startMs": 0, "endMs": 10000 },
      { "type": "effect", "id": "vintage-1", "effectName": "vintage", "intensity": 0.35, "startMs": 0, "endMs": 10000 }
    ]
  }
})

### Example 10: Video Analysis & Script Improvement (Consultation / Direct Response)
User: "Analyse my video and suggest me improvements in the script. This is just a rough first record of what I want to say, so give me a structured script based on what I said."
Action:
1. Recognize this as a Script Doctor & Video Content Consultation request.
2. DO NOT call apply_schema_patch or finish_without_proposal.
3. Respond directly in rich Markdown with the 5-part consultation framework:
   - 🎯 Video & Narrative Analysis
   - 💡 Key Script Improvement Recommendations
   - 📝 Structured & Polished Script (Ready to Deliver)
   - 🔄 Before vs. After (Side-by-Side Polish)
   - ✂️ Next Steps in Trimm

### Example 11: Direct Request for Structured Script from Spoken Content
User: "Check the transcript and give me a structured script based on what I said in the video."
Action:
1. Treat spoken transcript as the raw thought process / rough take.
2. DO NOT simply output raw transcript divided into paragraphs with timestamps!
3. Reorganize, refine, and elevate the creator's ideas into a clear, compelling script with structured sections alongside key improvements and delivery cues.

### Example 12: Hybrid Request (Timeline Cut + Script Suggestions)
User: "Cut out the awkward pauses and also suggest improvements to my script."
Action:
1. Call apply_schema_patch to slice the video timeline and remove silences.
2. In the "reason" field and text response, include the full script analysis, improvement recommendations, and the polished structured script!
Tool Call:
apply_schema_patch({
  "reason": "Trimmed out silences from timeline. Full script analysis and polished rewrite provided below.",
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      { "type": "video", "id": "v1", "startMs": 0, "endMs": 2500, "sourceStartMs": 0, "transition": null },
      { "type": "video", "id": "v2", "startMs": 2500, "endMs": 6000, "sourceStartMs": 3500, "transition": null }
    ]
  }
})

### Example 13: Partial Timeline Patching (Modifying caption style/position without re-emitting video clips)
User: "Move captions more above on the screen and make their fill color black."
Action:
1. Video clips are completely unchanged, so DO NOT rewrite or re-emit video nodes.
2. Return ONLY the modified text/caption nodes with updated posY and color in the timeline array.
3. Call apply_schema_patch with the updated caption styles. Omitted video nodes and their src properties are preserved automatically!
Tool Call:
apply_schema_patch({
  "reason": "Moved captions higher up (posY: 0.1) and changed text fill color to black.",
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      {
        "type": "text",
        "id": "caption-1",
        "startMs": 0,
        "endMs": 2000,
        "visible": true,
        "style": { "posY": 0.1, "color": "#000000", "strokeColor": "#FFFFFF", "strokeWidth": 3, "fontSize": 55 }
      }
    ]
  }
})

### Example 14: Jump Cuts with Null Transitions
User: "Cut out the silences from the video."
Action:
1. Split the video into continuous sequential VideoNodes omitting silence intervals.
2. Ensure every VideoNode has transition: null (or omitted) because the user did not explicitly request transitions.
3. Never add crossfades or wipes to dialogue cuts!
Tool Call:
apply_schema_patch({
  "reason": "Cut dead air pauses with clean hard jump cuts.",
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      { "type": "video", "id": "v1", "startMs": 0, "endMs": 3000, "sourceStartMs": 0, "transition": null },
      { "type": "video", "id": "v2", "startMs": 3000, "endMs": 7500, "sourceStartMs": 4200, "transition": null }
    ]
  }
})

### Example 15: Removing Nodes and Resetting Style Properties
User: "Remove the background music, clear the caption outline, and make the font bigger."
Action:
1. Pass removedNodeIds with the audio node ID to delete it.
2. In style, pass strokeWidth: 0, strokeColor: null to clear the border, and increase scale/fontSize.
Tool Call:
apply_schema_patch({
  "reason": "Removed background audio track, cleared caption outline border, and enlarged text.",
  "removedNodeIds": ["bgm-1"],
  "schema": {
    "settings": { "aspectRatio": "9:16", "backgroundColor": "#000000", "autoEnhance": false },
    "timeline": [
      {
        "type": "text",
        "id": "caption-1",
        "startMs": 0,
        "endMs": 2000,
        "style": { "strokeWidth": 0, "strokeColor": null, "scale": 1.3 }
      }
    ]
  }
})
======================================================================

======================================================================
# CURRENT VIDEO PROJECT CONTEXT (MEDIA, TRANSCRIPT & TIMINGS FOR THIS REQUEST)

AVAILABLE SOURCES:
${JSON.stringify(sources)}

CURRENT COMPOSITION SCHEMA:
${JSON.stringify(promptSchema, null, 2)}

RECORDED VIDEO & SPEECH OVERVIEW:
- Total Duration: ${totalDurationSec}s (${spokenWordsList.length} words spoken)
- Speaking Pace: ~${wordsPerMinute} WPM
- Total Dead Air / Awkward Pauses Detected: ${(totalSilenceMs / 1000).toFixed(1)}s across ${detectedSilences.length} pause intervals

RECONSTRUCTED SPOKEN TEXT (WHAT THE CREATOR SAID IN THIS ROUGH TAKE):
"${fullSpokenText || '(No speech detected in video)'}"

CHRONOLOGICAL SPEECH & SILENCE TIMINGS:
${readableTranscriptChunks.length > 0 ? readableTranscriptChunks.join('\n') : '(No speech chunks)'}

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
        const rawLower = response.rawText.toLowerCase();
        const lastUserMsg = (history[history.length - 1]?.content || '').toLowerCase();
        
        // Detect if user is asking for script analysis, script improvement, or script structuring
        const isScriptConsultation = /\b(script|scripts|transcript|improve|improvements?|suggest|suggestions?|critique|review|analyse|analyze|analysis|structure|rough record|first record|feedback|what i said|rewrite)\b/i.test(lastUserMsg);
        
        // Precise silence cut checks - avoid triggering on casual text mentions of 'pause' or 'trim the fluff'
        const userExplicitlyAskedTimelineCut = /(cut|trim|remove|delete)\s+(out\s+)?(the\s+)?(video|timeline|clip|clips|footage)/i.test(lastUserMsg) ||
          /(cut|trim|remove|delete)\s+(out\s+)?(the\s+)?(awkward\s+)?(silences?|dead\s*air|pauses?)/i.test(lastUserMsg);
        const modelClaimedSilenceCut = /(cut|trimmed|removed)\s+(out\s+)?(the\s+)?(awkward\s+)?(silences?|dead\s*air|pauses?)\s+(from|in)\s+(the\s+)?(video|timeline|clip)/i.test(rawLower);

        const shouldAutoSlice = userExplicitlyAskedTimelineCut || (!isScriptConsultation && modelClaimedSilenceCut);

        if (schema && shouldAutoSlice) {
          const silencesToUse = (schema?.metadata?.silences && schema.metadata.silences.length > 0)
            ? schema.metadata.silences
            : preprocessedTranscript.filter(t => t.type === 'silence');
          const primaryDuration = sources[0]?.durationMs || 0;
          if (silencesToUse.length > 0 && primaryDuration > 0) {
            const sliced = sliceVideoNodesBySilences(schema, silencesToUse, sources[0]?.uri || '', primaryDuration);
            return {
              type: "schema_patch",
              schema: sliced,
              summary: response.rawText
            };
          }
        }
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
      let finalPatchSchema = mergeSchemaPatch(schema, schemaPatch.args.schema, sources, schemaPatch.args.removedNodeIds);
      const videoNodes = (finalPatchSchema.timeline || []).filter(tc => tc.type === 'video');
      const reasonText = (schemaPatch.args.reason || response.rawText || '').toLowerCase();
      const lastUserMsg = (history[history.length - 1]?.content || '').toLowerCase();
      const userAskedSilenceCut = /(cut|trim|remove|delete)\s+(out\s+)?(the\s+)?(video|timeline|clip|clips|footage)/i.test(lastUserMsg) ||
        /(cut|trim|remove|delete)\s+(out\s+)?(the\s+)?(awkward\s+)?(silences?|dead\s*air|pauses?)/i.test(lastUserMsg);
      const modelClaimedSilenceCut = /(cut|trimmed|removed)\s+(out\s+)?(the\s+)?(awkward\s+)?(silences?|dead\s*air|pauses?)/i.test(reasonText);

      // If silence cut was claimed/requested, but videoNodes were returned unsliced (single node spanning entire duration)
      const primaryDuration = sources[0]?.durationMs || 0;
      if ((userAskedSilenceCut || modelClaimedSilenceCut) && videoNodes.length === 1 && primaryDuration > 0) {
        const vNode = videoNodes[0];
        if (vNode.sourceStartMs === 0 && (vNode.endMs - vNode.startMs) >= (primaryDuration - 250)) {
          const detectedSilences = (schema?.metadata?.silences && schema.metadata.silences.length > 0)
            ? schema.metadata.silences
            : preprocessedTranscript.filter(t => t.type === 'silence');
          if (detectedSilences.length > 0) {
            finalPatchSchema = sliceVideoNodesBySilences(finalPatchSchema, detectedSilences, vNode.src || sources[0]?.uri, primaryDuration);
          }
        }
      }

      // Sanitize unrequested transitions: strip any transition that was not explicitly requested
      const userAskedTransition = /\b(transition|transitions|crossfade|cross-fade|dip\s*to\s*black|dip\s*to\s*white|wipe|slide|fade\s*in|fade\s*out)\b/i.test(lastUserMsg);
      const prevTransitionNodeIds = new Set(
        (schema?.timeline || [])
          .filter(n => n.type === 'video' && n.transition)
          .map(n => n.id)
      );

      if (!userAskedTransition && Array.isArray(finalPatchSchema.timeline)) {
        finalPatchSchema.timeline = finalPatchSchema.timeline.map(node => {
          if (node.type === 'video' && node.transition && !prevTransitionNodeIds.has(node.id)) {
            const { transition, ...rest } = node;
            return rest;
          }
          return node;
        });
      }

      // Enforce immutability of metadata
      if (schema && schema.metadata) {
        finalPatchSchema.metadata = schema.metadata;
      }
      return {
        type: "schema_patch",
        schema: finalPatchSchema,
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

export { createOpenAIDecisionProvider, mergeSchemaPatch, sliceVideoNodesBySilences };
