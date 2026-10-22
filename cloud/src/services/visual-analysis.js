import OpenAI from 'openai';

/**
 * VisualSourceProfile defines the semantic visual metadata extracted from video footage.
 * This is cached in the project after the first pass, enabling zero-cost subsequent edits.
 */

const DEFAULT_SYSTEM_PROMPT = `You are an expert film director and technical video editor assistant.
Analyze the provided chronological keyframe sequence from a video clip and produce a concise, structured JSON assessment.
Pay close attention to visual changes across time from the first frame to the last frame.

Return ONLY a JSON object conforming to this exact structure:
{
  "summary": "1-sentence description of what happens visually in the clip across time",
  "setting": "exterior_daylight" | "exterior_night" | "interior_workspace" | "interior_home" | "interior_public" | "other",
  "shotScale": "wide" | "medium" | "close_up" | "macro",
  "subjects": ["string", "string"],
  "activity": "brief description of primary action progression (e.g. typing on keyboard, walking down street, food steaming)",
  "cameraMotion": "static" | "pan_left" | "pan_right" | "tilt" | "push_in" | "pull_back" | "handheld_smooth" | "handheld_shaky",
  "usableRange": {
    "startMs": integer (suggested safe cut-in timestamp in ms, skipping any initial camera tap, autofocus hunting, or phone shake),
    "endMs": integer (suggested safe cut-out timestamp in ms before clip end or camera drop)
  },
  "narrativeTags": ["tag1", "tag2"]
}`;

export class VisualAnalysisService {
  constructor(options = {}) {
    const apiKey = options.apiKey || process.env.GEMINI_API_KEY || process.env.VISION_API_KEY || process.env.OPENAI_API_KEY;
    const baseURL = options.baseURL || process.env.VISION_API_BASE_URL || 'https://generativelanguage.googleapis.com/v1beta/openai/';
    const model = options.model || process.env.VISION_MODEL || 'gemini-flash-latest';

    this.model = model;
    this.baseURL = baseURL;
    this.cache = new Map(); // In-memory fingerprint cache

    if (options.client) {
      this.client = options.client;
    } else if (apiKey) {
      this.client = new OpenAI({
        apiKey,
        baseURL
      });
    } else {
      this.client = null;
    }
  }

  /**
   * Discovers available models via standard OpenAI-compatible models.list API
   * @returns {Promise<string[]>} Array of model IDs
   */
  async listAvailableModels() {
    if (!this.client) return [];
    try {
      const response = await this.client.models.list();
      if (Array.isArray(response?.data)) {
        return response.data.map(m => m.id);
      }
      const models = [];
      for await (const m of response) {
        models.push(m.id);
      }
      return models;
    } catch (err) {
      console.warn(`[VisualAnalysisService] Failed to list models: ${err.message}`);
      return [];
    }
  }

  /**
   * Backward-compatible helper for model discovery
   */
  static async listAvailableGeminiModels(apiKey = process.env.GEMINI_API_KEY) {
    const service = new VisualAnalysisService({ apiKey });
    return service.listAvailableModels();
  }

  /**
   * Analyzes an array of video sources with their representative extracted frames.
   * @param {Array<{ fingerprint: string, durationMs: number, frames: Array<{ atMs: number, base64: string, mimeType?: string }> }>} sources
   * @returns {Promise<Record<string, object>>} Map of fingerprint -> VisualSourceProfile
   */
  async analyzeSources(sources = []) {
    if (!Array.isArray(sources) || sources.length === 0) {
      return {};
    }

    const results = {};

    for (const source of sources) {
      const { fingerprint, durationMs = 5000, frames = [] } = source;
      if (!fingerprint) continue;

      // Check cache first (Analyze Once principle)
      if (this.cache.has(fingerprint)) {
        results[fingerprint] = this.cache.get(fingerprint);
        continue;
      }

      // If client is unavailable or no frames provided, generate safe fallback profile
      if (!this.client || frames.length === 0) {
        const fallback = this.generateFallbackProfile(source);
        results[fingerprint] = fallback;
        this.cache.set(fingerprint, fallback);
        continue;
      }

      try {
        const profile = await this.analyzeSingleSource(source);
        results[fingerprint] = profile;
        this.cache.set(fingerprint, profile);
      } catch (err) {
        console.warn(`[VisualAnalysis] Failed to analyze source ${fingerprint}: ${err.message}. Using safe fallback.`);
        const fallback = this.generateFallbackProfile(source);
        results[fingerprint] = fallback;
        this.cache.set(fingerprint, fallback);
      }
    }

    return results;
  }

  async analyzeSingleSource(source) {
    const { durationMs = 5000, frames = [] } = source;
    
    const content = [
      {
        type: 'text',
        text: `Analyze this video clip (Duration: ${durationMs}ms). Here are ${frames.length} keyframes extracted at various timestamps:`
      }
    ];

    for (const frame of frames) {
      const mime = frame.mimeType || 'image/jpeg';
      content.push({
        type: 'text',
        text: `[Frame at ${frame.atMs || 0}ms]:`
      });
      content.push({
        type: 'image_url',
        image_url: {
          url: frame.base64.startsWith('data:') ? frame.base64 : `data:${mime};base64,${frame.base64}`
        }
      });
    }

    const response = await this.client.chat.completions.create({
      model: this.model,
      messages: [
        { role: 'system', content: DEFAULT_SYSTEM_PROMPT },
        { role: 'user', content }
      ],
      response_format: { type: 'json_object' }
    });

    const rawText = response.choices?.[0]?.message?.content?.trim();
    if (!rawText) throw new Error('Empty response from vision model');

    let cleanedText = rawText;
    if (cleanedText.startsWith('```')) {
      cleanedText = cleanedText.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '');
    }

    const parsed = JSON.parse(cleanedText);
    
    // Normalize and validate
    return {
      summary: parsed.summary || 'Video clip',
      setting: parsed.setting || 'other',
      shotScale: parsed.shotScale || 'medium',
      subjects: Array.isArray(parsed.subjects) ? parsed.subjects : [],
      activity: parsed.activity || '',
      cameraMotion: parsed.cameraMotion || 'handheld_smooth',
      usableRange: {
        startMs: typeof parsed.usableRange?.startMs === 'number' ? parsed.usableRange.startMs : 500,
        endMs: typeof parsed.usableRange?.endMs === 'number' ? parsed.usableRange.endMs : durationMs
      },
      narrativeTags: Array.isArray(parsed.narrativeTags) ? parsed.narrativeTags : []
    };
  }

  generateFallbackProfile(source) {
    const duration = source.durationMs || 5000;
    return {
      summary: 'General video footage',
      setting: 'other',
      shotScale: 'medium',
      subjects: [],
      activity: '',
      cameraMotion: 'handheld_smooth',
      usableRange: {
        startMs: 500,
        endMs: duration
      },
      narrativeTags: []
    };
  }
}
