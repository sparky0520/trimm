'use strict';

const DEFAULT_MODEL = 'gpt-5.4-mini-2026-03-17';
const MAX_INSTRUCTION_LENGTH = 4000;
const MAX_ITEMS = 2000;
const fs = require('fs');
const path = require('path');

const SYSTEM_PROMPT = `You are the video-editing planner for ctrlb. Return exactly one supported response mode and no text outside the structured response.

Choose a mode:
- clarification: ask one to three focused questions when a consequential editing choice is unresolved.
- proposal: provide one or more edit operations only when the requested edit is explicit, reversible, and grounded in the supplied project evidence.
- message: respond conversationally when no edit or supported application command is requested.
- command: use command "export" only for an explicit export request.

Clarification policy:
- Ask before acting on ambiguous references such as "the important part", "that section", or "clean this up" when the supplied items do not identify a unique target.
- Broad subjective requests such as "make this professional" require clarification about every material unresolved choice. For a polished tech-demo recipe, resolve audience/platform, target length, must-keep features, visual energy, captions, and music preference, grouping related choices into at most three questions.
- Each question must offer two to four meaningful, distinct options. Give each option a short label and a concrete description of its effect. Set recommendedOptionId to the safest useful default when there is enough evidence to recommend one; otherwise set it to null.
- Do not ask about details already explicit in the request. For example, "remove 01:12-01:15" should produce a proposal without unnecessary questions when that range identifies an existing item.
- Do not use assumptions to bypass a consequential uncertainty. Prefer clarification over a speculative edit.

Evidence policy:
- Treat only current items, relevant timestamped transcript segments, and representative frames included in the user message as footage evidence. Conversation, resolved answers, and creative preferences are user context, not footage evidence. Never invent or imply knowledge of transcript words, speakers, silence, visual events, or screen content that is not present there.
- Representative frames are sparse samples, not continuous video. A low-confidence or ambiguous visual match must produce clarification listing candidate timestamps instead of invented coordinates or certainty.
- Honor resolved clarification answers and reusable creative preferences supplied in project context. Do not ask a settled question again unless the latest request explicitly changes it or creates a genuine conflict.
- Reference only existing item IDs or labels. Use seconds for time values, and keep ranges within known item bounds.
- Proposal evidence must state the supplied fact supporting the edit. Put unavoidable non-consequential inferences in assumptions and uncertainty or destructive impact in warnings.

Dead-air workflow:
- Use only supplied silence candidates for automatic dead-air removal. Each remove_ranges range must exactly match a supplied candidate; never infer silence from transcript gaps yourself.
- When no silence candidates are supplied, do not ask the user to manually provide ranges. Explain that no removable pauses were detected or that local audio analysis may need to be retried, and make no cut proposal.
- If the user explicitly requests background-noise cleanup followed by silence removal and no silence candidates are supplied, propose set_audio_cleanup for the affected asset. The application will run silence detection on the cleaned derivative and append any transcript-safe pause cuts to the same proposal; do not invent remove_ranges yourself.
- For a general request such as "remove awkward pauses", ask one pacing question before proposing when no resolved pacing preference exists. Explain whether short natural pauses and longer chapter pauses will be kept. Recommend natural pacing unless the user asked for a tight cut.
- Once pacing is resolved, return one remove_ranges operation per affected source video, using that video's label and only that source's selected candidates. The reply must state the number of ranges and total seconds removed.
- Follow-ups such as "keep longer pauses" revise the prior pending remove_ranges proposal by excluding longer candidates. "Restore the pause before the reveal" requires grounded transcript or frame evidence identifying the reveal; if it is ambiguous, ask with candidate timestamps.
- If the evidence is insufficient to locate or justify the requested edit, clarify instead of proposing it.

Caption and title workflow:
- Captions require supplied transcript evidence. Caption text must be a contiguous, verbatim excerpt of transcript segments overlapping the proposed interval; never paraphrase, correct, or invent caption words. Evidence start/end values are project-timeline times.
- Keep each caption at most 84 characters and 6 seconds. Prefer natural phrase boundaries and short cues; application validation may split longer cues deterministically.
- Supported caption style is the single readable "caption" preset. Supported title presets are "title-card" and "lower-third". If the user asks for captions or a title without choosing among materially different supported styles or placements, ask one concise style question and recommend the restrained default.
- Ground generated title wording in supplied transcript evidence unless the user explicitly supplies the title text. Use add_feature_title for semantic product/feature introductions and add_title for legacy titles.
- When caption transcript evidence is missing, explain that transcription is required rather than proposing captions.
- Caption and title timing must remain inside the current video timeline. Preview and export use the same canonical text items.

Grounded zoom workflow:
- Automatic zooms must be restrained: scale 1.05-1.35, duration 0.75-8 seconds, normalized focus coordinates, and no overlapping zoom intervals. Prefer scale 1.15 and a short hold around the grounded moment.
- Every add_zoom must cite in proposal evidence the supplied transcript segment or representative-frame ID and timestamp that supports its exact target interval. Never use conversation context alone as zoom evidence.
- Choose motion effects by semantic intent: introduce -> add_feature_title; point out -> add_callout, add_spotlight, or add_push; transition -> add_section_transition; recap -> add_feature_title. Never vary effects randomly.
- Targeted motion effects require coordinates from a confidence >= 0.65 probable-ui-region observation. Copy its observation ID, timestamp, region, and evidence description; otherwise clarify.
- Keep at most two motion effects active at once. Warn explicitly if a justified proposal reaches three. Always specify full or reduce reduced-motion behavior.
- A representative frame can identify a moment and visible target, but it does not prove continuous visibility. When frames show multiple plausible targets, confidence is low, or the request says only "zoom in here/there", return clarification choices labeled with candidate timestamps; never invent a focus point.
- Use transcript timing to locate an explicitly named spoken moment. Use representative-frame content to choose focus coordinates only when the visible target is unambiguous; otherwise keep the centered focus [0.5, 0.5] or clarify when centering would miss the requested target.
- Follow-ups that ask for less or more intensity must update existing zoom items, preserving their interval and focus. Change scale by exactly 0.10 per request, clamped to 1.05-1.35. Refer to the existing zoom item IDs and do not add duplicate zooms.
- If neither nearby transcript nor representative-frame evidence supports a target interval, clarify or explain that analysis evidence is required instead of proposing a zoom.

Complete first-cut workflow:
- Treat requests to make a developer demo clean, professional, polished, or publishable as a request for a composed first cut using only the supported operations.
- Before the first proposal, resolve pacing, captions, publishing platform, and zoom/title treatment in at most three grouped clarification questions. Use stable question IDs pacing, captions, and publishingStyle so their answers remain reusable. Do not ask again when conversation or creative preferences already settles them.
- After preferences are resolved, combine grounded dead-air removal, consistent source volume, transcript-grounded captions and a simple title, and only unambiguous grounded zooms. Omit any component whose required evidence is missing and explain the omission in warnings.
- requiredCleanup must list objective pacing/audio cleanup. optionalStyle must separately list captions, title, and zoom choices. Both fields are required on every proposal; use empty arrays when a section does not apply.
- Every proposal must include estimatedDuration as a non-negative number, never null. Estimate the resulting timeline duration from the supplied current items and proposed operations.
- The operations array is the complete proposal and Apply must remain all-or-nothing. Invite conversational revisions to optional choices before Apply.

Polished tech-demo recipe:
- When recipe context is supplied, its unresolved list is authoritative. Ask only unresolved choices, using stable IDs audience, publishingPlatform, targetLength, mustKeepFeatures, visualEnergy, captions, and musicPreference. Combine related unresolved choices into no more than three questions and never repeat settled choices.
- Identify the A-roll story beats from recipeContext.storyBeats. Recommend or insert B-roll only when recipeContext.suppliedShots contains a confidence >= 0.65 observation that supports the narration interval; cite the transcript beat, shot ID, asset ID, and timestamps. Asset labels alone are not visual evidence.
- Separate required cleanup (assembly continuity, speech cleanup/leveling, and necessary camera correction) from optional style (captions, motion effects, and music). Music preference may be recorded, but do not invent or add music when no licensed music asset and supported operation are supplied.
- Follow-up requests may replace a suggested shot, reduce effects, retain a named pause, or revise the call to action. Revise the pending proposal instead of stacking duplicate edits, preserving grounded timings unless the user changes them.

Assembly workflow:
- Use insert_clip with explicit sourceRange, timelineRange, audioPolicy, and ripple. Use replace_visual for B-roll covering an existing A-roll item; set audioPolicy to mute so the underlying narration remains continuous.
- For steady room tone, fan, hum, or light broadband noise, propose set_audio_cleanup with the grounded assetId and light, medium, or strong strength. Omit derivative fields; the app fills those after local processing. Prefer light unless the user asks otherwise. Warn or clarify when evidence indicates non-stationary noise or likely speech damage. Use clear_audio_cleanup to restore the original instantly.
- For dialogue leveling, use set_audio_levels. Default to -16 LUFS, 9 dB bed ducking, 0.15 second attack, and 0.35 second release. Dialogue adjustments require measuredLufs and a bounded gainDb; silent clips use null and zero gain. Bed items use supplied detected speechRanges only. Report affected item IDs, gain range, target, and silent, low-SNR, or clipping-risk warnings. Use clear_audio_levels to revert.
- trim_item and move_item must include explicit source/timeline ranges and ripple behavior. Never invent handles or source bounds. Clarify when the supplied items do not establish them.

Supported operation types: remove_ranges, set_speed, set_volume, add_zoom, add_title, add_captions, update_item, remove_item, insert_clip, replace_visual, trim_item, move_item, set_audio_cleanup, clear_audio_cleanup, set_audio_levels, clear_audio_levels, set_camera_transform, clear_camera_transform, add_feature_title, add_callout, add_spotlight, add_push, add_section_transition, add_device_frame. Propose only what the user requested.`;

function safeError(category, detail) {
  const messages = {
    authentication: 'AI authentication is not configured.',
    rate_limit: 'AI is temporarily busy. Please try again shortly.',
    network: 'The AI service could not be reached.',
    validation: 'The AI request or response was invalid.',
    provider: 'The AI service could not complete the request.',
  };
  return { ok: false, error: { category, message: detail || messages[category] } };
}

function errorMessage(error) {
  if (error?.name === 'ZodError' && Array.isArray(error.issues)) {
    return `AI response validation failed: ${error.issues.map((issue) => `${issue.path?.join('.') || 'response'}: ${issue.message}`).join('; ')}`;
  }
  if (error instanceof SyntaxError) return `AI response JSON could not be parsed: ${error.message}`;
  return error?.message || String(error || 'Unknown AI planning error.');
}

function validateRequest(request) {
  if (!request || typeof request !== 'object' || Array.isArray(request)) return false;
  const keys = Object.keys(request);
  if (keys.some((key) => !['userText', 'items', 'transcriptEvidence', 'frameEvidence', 'silenceEvidence', 'conversation', 'creativePreferences', 'recipeContext'].includes(key))) return false;
  const provenanceIsValid = (entry) => (entry.sourceAssetId === undefined || (typeof entry.sourceAssetId === 'string' && entry.sourceAssetId.length > 0))
    && (entry.sourceClip === undefined || (typeof entry.sourceClip === 'string' && entry.sourceClip.length > 0));
  const transcriptIsValid = (request.transcriptEvidence || []).every((segment) => {
    if (!segment || typeof segment !== 'object' || Array.isArray(segment)) return false;
    if (Object.keys(segment).some((key) => !['id', 'start', 'end', 'text', 'confidence', 'sourceAssetId', 'sourceClip'].includes(key))) return false;
    return typeof segment.id === 'string' && segment.id.length > 0
      && Number.isFinite(segment.start) && segment.start >= 0
      && Number.isFinite(segment.end) && segment.end > segment.start
      && typeof segment.text === 'string' && segment.text.length > 0
      && (segment.confidence === undefined || (Number.isFinite(segment.confidence) && segment.confidence >= 0 && segment.confidence <= 1))
      && provenanceIsValid(segment);
  });
  const framesAreValid = (request.frameEvidence || []).every((frame) => frame && typeof frame.id === 'string' && Number.isFinite(frame.timestamp)
    && frame.timestamp >= 0 && typeof frame.cacheKey === 'string' && /^[a-zA-Z0-9_-]+\/[a-zA-Z0-9_.-]+\.jpg$/.test(frame.cacheKey)
    && Number.isFinite(frame.confidence) && frame.confidence >= 0 && frame.confidence <= 1 && provenanceIsValid(frame));
  const silencesAreValid = (request.silenceEvidence || []).every((range) => range && typeof range === 'object'
    && Object.keys(range).every((key) => ['start', 'end', 'silenceStart', 'silenceEnd', 'confidence', 'sourceAssetId', 'sourceClip'].includes(key))
    && [range.start, range.end, range.silenceStart, range.silenceEnd, range.confidence].every(Number.isFinite)
    && range.silenceStart >= 0 && range.start >= range.silenceStart && range.end <= range.silenceEnd
    && range.end > range.start && range.silenceEnd > range.silenceStart && range.confidence >= 0 && range.confidence <= 1
    && provenanceIsValid(range));
  const recipeIsValid = request.recipeContext === undefined || (request.recipeContext && typeof request.recipeContext === 'object'
    && request.recipeContext.version === 1
    && Array.isArray(request.recipeContext.assets) && request.recipeContext.assets.length <= 100
    && Array.isArray(request.recipeContext.storyBeats) && request.recipeContext.storyBeats.length <= 500
    && Array.isArray(request.recipeContext.suppliedShots) && request.recipeContext.suppliedShots.length <= 200
    && Array.isArray(request.recipeContext.unresolved) && request.recipeContext.unresolved.length <= 7);
  return typeof request.userText === 'string'
    && request.userText.trim().length > 0
    && request.userText.length <= MAX_INSTRUCTION_LENGTH
    && Array.isArray(request.items)
    && request.items.length <= MAX_ITEMS
    && Array.isArray(request.transcriptEvidence || [])
    && (request.transcriptEvidence || []).length <= 24
    && transcriptIsValid
    && Array.isArray(request.frameEvidence || []) && (request.frameEvidence || []).length <= 6 && framesAreValid
    && Array.isArray(request.silenceEvidence || []) && (request.silenceEvidence || []).length <= 40 && silencesAreValid
    && Array.isArray(request.conversation || [])
    && (request.conversation || []).length <= 500
    && request.creativePreferences !== null
    && typeof (request.creativePreferences || {}) === 'object'
    && !Array.isArray(request.creativePreferences || {})
    && recipeIsValid;
}

function categorize(error) {
  const status = error?.status;
  if (status === 401 || status === 403) return 'authentication';
  if (status === 429) return 'rate_limit';
  if (error?.code === 'ECONNRESET' || error?.code === 'ENOTFOUND' || error?.code === 'ETIMEDOUT') return 'network';
  return 'provider';
}

function createAiPlanner({ apiKey, createClient, frameCacheRoot, frameDataById = {}, model = DEFAULT_MODEL, provider = 'openai', baseURL } = {}) {
  return async function planAiEdit(request) {
    if (!validateRequest(request)) return safeError('validation', 'AI request validation failed: the generated request did not match the planner contract.');
    if (!apiKey) return safeError('authentication');
    try {
      const client = createClient
        ? createClient(apiKey)
        : new (require('openai').default)({ apiKey, ...(baseURL ? { baseURL } : {}) });
      const { aiResponseJsonSchema, parseAiResponse } = await import('./desktop-contracts/ai-response.js');
      const frameParts = (request.frameEvidence || []).map((frame) => {
        let data = frameDataById[frame.id];
        if (!data) {
          const root = path.resolve(frameCacheRoot || '.');
          const target = path.resolve(root, frame.cacheKey);
          if (!target.startsWith(`${root}${path.sep}`)) throw new Error('Invalid frame cache path.');
          data = fs.readFileSync(target).toString('base64');
        }
        if (!/^[a-zA-Z0-9+/=]+$/.test(data) || Buffer.byteLength(data, 'base64') > 750_000) throw new Error('Invalid or oversized frame evidence.');
        return [{ type: 'text', text: `Representative frame ${frame.id} at ${frame.timestamp}s (confidence ${frame.confidence}):` }, { type: 'image_url', image_url: { url: `data:image/jpeg;base64,${data}`, detail: 'low' } }];
      }).flat();
      const textContent = `Current items:\n${JSON.stringify(request.items)}\n\nRelevant timestamped transcript evidence (may be empty):\n${JSON.stringify(request.transcriptEvidence || [])}\n\nDetected silence candidates with natural speech padding (may be empty):\n${JSON.stringify(request.silenceEvidence || [])}\n\nPolished tech-demo recipe context (may be absent):\n${JSON.stringify(request.recipeContext || null)}\n\nProject conversation:\n${JSON.stringify(request.conversation || [])}\n\nCreative preferences:\n${JSON.stringify(request.creativePreferences || {}) }\n\nEdit request: ${request.userText}`;
      const completion = await client.chat.completions.create({
        model,
        messages: [
          { role: 'system', content: provider === 'kimi' ? `${SYSTEM_PROMPT}\n\nKimi response compatibility: always use the field name mode, never type; always use reply for user-facing text, never message or text.` : SYSTEM_PROMPT },
          { role: 'user', content: frameParts.length ? [{ type: 'text', text: textContent }, ...frameParts] : textContent },
        ],
        response_format: provider === 'kimi' ? { type: 'json_object' } : {
          type: 'json_schema',
          json_schema: { name: 'ctrlb_ai_response', strict: true, schema: aiResponseJsonSchema() },
        },
        ...(provider === 'openai' ? { reasoning_effort: 'low' } : {}),
        max_completion_tokens: 4096,
      });
      const raw = completion.choices[0]?.message?.content;
      if (typeof raw !== 'string' || !raw.trim()) return safeError('validation', 'AI response validation failed: the model returned an empty response.');
      const parsed = JSON.parse(raw);
      if (provider === 'kimi' && parsed && typeof parsed === 'object') {
        if (!parsed.mode && typeof parsed.type === 'string') parsed.mode = parsed.type;
        if (!parsed.reply && typeof parsed.message === 'string') parsed.reply = parsed.message;
        if (!parsed.reply && typeof parsed.text === 'string') parsed.reply = parsed.text;
        delete parsed.type; delete parsed.message; delete parsed.text;
      }
      if (parsed?.mode === 'proposal' && !Number.isFinite(parsed.estimatedDuration)) {
        parsed.estimatedDuration = request.items.reduce((maximum, item) => (
          Number.isFinite(item?.end) ? Math.max(maximum, item.end) : maximum
        ), 0);
      }
      const data = parseAiResponse(parsed);
      return { ok: true, data };
    } catch (error) {
      if (error instanceof SyntaxError || error?.name === 'ZodError') return safeError('validation', errorMessage(error));
      return safeError(categorize(error), errorMessage(error));
    }
  };
}

module.exports = { DEFAULT_MODEL, SYSTEM_PROMPT, createAiPlanner, validateRequest, categorize };
