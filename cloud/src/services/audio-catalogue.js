import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// 14 Music moods
const MUSIC_MOODS = [
  'ambient_calm', 'chill_lofi', 'cinematic_epic', 'corporate_inspiring',
  'dark_moody', 'emotional_piano', 'energetic_electronic', 'festive_celebration',
  'funky_groove', 'happy_playful', 'indian_fusion', 'romantic_soft',
  'upbeat_pop', 'vlog_acoustic'
];

// 29 SFX categories
const SFX_CATEGORIES = [
  'whoosh', 'swipe', 'riser', 'page_turn',
  'impact', 'bass_drop', 'explosion', 'stinger',
  'click', 'pop', 'ding', 'shutter', 'typing', 'tick', 'cash', 'success', 'buzzer',
  'laugh', 'gasp', 'crowd', 'cartoon', 'sci_fi', 'glitch', 'sparkle',
  'knock', 'heartbeat', 'splash', 'alarm'
];

// Keyword to mood mapping for fast natural language matching
const MOOD_KEYWORDS = {
  chill_lofi: ['lofi', 'chill', 'lo-fi', 'relaxing', 'study', 'mellow', 'laid back', 'vinyl', 'rhodes', 'chillout', 'lofi beat'],
  upbeat_pop: ['upbeat', 'pop', 'happy', 'bright', 'cheerful', 'dance', 'catchy', 'sunny', 'energetic pop', 'feel-good'],
  cinematic_epic: ['cinematic', 'epic', 'trailer', 'dramatic', 'orchestral', 'heroic', 'suspense', 'powerful', 'blockbuster'],
  emotional_piano: ['piano', 'sad', 'emotional', 'heartfelt', 'slow', 'nostalgic', 'tender', 'gentle piano', 'ballad'],
  energetic_electronic: ['electronic', 'edm', 'techno', 'synthwave', 'workout', 'fast', 'future bass', 'high energy', 'club', 'beat'],
  happy_playful: ['playful', 'quirky', 'ukulele', 'fun', 'kids', 'whistle', 'glockenspiel', 'comedy', 'bouncy', 'carefree'],
  corporate_inspiring: ['corporate', 'inspiring', 'motivational', 'business', 'presentation', 'tech', 'clean', 'modern', 'minimal'],
  vlog_acoustic: ['acoustic', 'vlog', 'travel', 'folk', 'guitar', 'fingerpicking', 'easygoing', 'breezy', 'casual'],
  dark_moody: ['dark', 'moody', 'trap', '808', 'brooding', 'distorted', 'creepy', 'menacing', 'eerie', 'horror'],
  romantic_soft: ['romantic', 'love', 'wedding', 'soft', 'intimate', 'sweet', 'warm', 'delicate', 'sensual'],
  funky_groove: ['funk', 'funky', 'groove', 'disco', 'slap bass', 'stylish', 'cool', 'danceable', 'rhythm'],
  ambient_calm: ['ambient', 'calm', 'meditation', 'drone', 'atmospheric', 'spa', 'peaceful', 'serene', 'subtle', 'sleep'],
  indian_fusion: ['indian', 'sitar', 'tabla', 'bollywood', 'desi', 'dhol', 'bansuri', 'fusion'],
  festive_celebration: ['celebration', 'festive', 'party', 'festival', 'triumph', 'brass', 'joyful', 'carnival', 'cheering']
};

// Keyword to SFX category mapping
const SFX_KEYWORDS = {
  whoosh: ['whoosh', 'swoosh', 'swish', 'air', 'flyby', 'fast pass', 'whip'],
  swipe: ['swipe', 'slide', 'page swipe', 'card swipe'],
  riser: ['riser', 'build up', 'buildup', 'swell', 'sweep up', 'anticipation', 'rising'],
  page_turn: ['page turn', 'paper', 'flip', 'book', 'paper rustle', 'sheet'],
  impact: ['impact', 'hit', 'slam', 'boom', 'punch', 'strike', 'thud', 'smash', 'heavy hit', 'drop'],
  bass_drop: ['bass drop', 'sub drop', '808 drop', 'sub bass', 'low end', 'boom drop'],
  explosion: ['explosion', 'blast', 'detonation', 'bomb', 'firework', 'burst'],
  stinger: ['stinger', 'reveal', 'dramatic hit', 'accent hit', 'chord sting'],
  click: ['click', 'tap', 'mouse', 'button', 'switch', 'press', 'toggle', 'ui click'],
  pop: ['pop', 'bubble', 'bubble pop', 'plop', 'cork', 'balloon'],
  ding: ['ding', 'bell', 'chime', 'notification', 'bing', 'alert ding', 'ping'],
  shutter: ['shutter', 'camera', 'photo', 'snapshot', 'flash'],
  typing: ['typing', 'keyboard', 'typewriter', 'keys', 'keystroke', 'texting'],
  tick: ['tick', 'clock', 'countdown', 'stopwatch', 'timer', 'ticking'],
  cash: ['cash', 'coins', 'money', 'register', 'cha-ching', 'ka-ching', 'payment', 'dollar'],
  success: ['success', 'win', 'victory', 'level up', 'achievement', 'reward', 'correct', 'fanfare'],
  buzzer: ['buzzer', 'wrong', 'error', 'fail', 'failure', 'denied', 'beep', 'bad'],
  laugh: ['laugh', 'laughter', 'giggle', 'chuckle', 'ha ha', 'funny'],
  gasp: ['gasp', 'shock', 'surprise', 'intake', 'breath', 'omg'],
  crowd: ['crowd', 'applause', 'cheer', 'clapping', 'audience', 'ovation'],
  cartoon: ['cartoon', 'boing', 'slide whistle', 'funny', 'spring', 'silly', 'goofy'],
  sci_fi: ['sci-fi', 'scifi', 'laser', 'futuristic', 'power up', 'space', 'teleport', 'cyber', 'robot'],
  glitch: ['glitch', 'static', 'digital error', 'vhs', 'corruption', 'distortion', 'tv static'],
  sparkle: ['sparkle', 'magic', 'twinkle', 'fairy', 'shimmer', 'glimmer', 'magical'],
  knock: ['knock', 'door knock', 'knocking', 'rap'],
  heartbeat: ['heartbeat', 'heart beat', 'pulse', 'thump thump', 'tension pulse'],
  splash: ['splash', 'water', 'drip', 'droplet', 'liquid'],
  alarm: ['alarm', 'siren', 'warning', 'emergency', 'alert', 'red alert']
};

class AudioCatalogueService {
  constructor(options = {}) {
    const defaultOrigin = process.env.BACKEND_URL || (process.env.NODE_ENV === 'production' ? 'https://trimm.fly.dev' : 'https://trimm.fly.dev');
    this.baseUrl = (options.baseUrl || process.env.AUDIO_BASE_URL || `${defaultOrigin}/v1/audio/assets`).replace(/\/+$/, '');
    this.assetsDir = this._resolveAssetsDir(options.assetsDir);
    this.cataloguePath = this._resolveCataloguePath(options.cataloguePath);

    this.items = [];
    this.byId = new Map();
    this.music = [];
    this.sfx = [];
    this.musicByMood = new Map();
    this.sfxByCategory = new Map();
    this.tokenIndex = new Map();

    this.loadCatalogue();
  }

  _resolveAssetsDir(explicitDir) {
    if (explicitDir && fs.existsSync(explicitDir)) return explicitDir;
    if (process.env.AUDIO_ASSETS_DIR && fs.existsSync(process.env.AUDIO_ASSETS_DIR)) {
      return process.env.AUDIO_ASSETS_DIR;
    }
    const localSymlink = path.resolve(__dirname, '../../assets/audio');
    if (fs.existsSync(localSymlink)) return localSymlink;
    const globalSource = path.resolve('/Users/darsh/audio generation/trimm_audio/audio');
    if (fs.existsSync(globalSource)) return globalSource;
    return localSymlink;
  }

  _resolveCataloguePath(explicitPath) {
    if (explicitPath && fs.existsSync(explicitPath)) return explicitPath;
    const localData = path.resolve(__dirname, '../data/catalogue.json');
    if (fs.existsSync(localData)) return localData;
    const globalData = path.resolve('/Users/darsh/audio generation/trimm_audio/catalogue.json');
    if (fs.existsSync(globalData)) return globalData;
    return localData;
  }

  loadCatalogue() {
    try {
      if (!fs.existsSync(this.cataloguePath)) {
        console.warn(`[AudioCatalogue] Catalogue file not found at: ${this.cataloguePath}`);
        return;
      }
      const raw = fs.readFileSync(this.cataloguePath, 'utf8');
      const data = JSON.parse(raw);
      const rawItems = Array.isArray(data.items) ? data.items : [];

      this.items = [];
      this.byId.clear();
      this.music = [];
      this.sfx = [];
      this.musicByMood.clear();
      this.sfxByCategory.clear();
      this.tokenIndex.clear();

      for (const item of rawItems) {
        const relPath = (item.file || '').replace(/^audio\//, '');
        const enriched = {
          ...item,
          relPath,
          url: `${this.baseUrl}/${relPath}`,
          tags: Array.isArray(item.tags) ? item.tags.map(t => String(t).toLowerCase()) : []
        };

        if (enriched.type === 'music') {
          enriched.mood = enriched.category || enriched.mood || 'ambient_calm';
        }

        this.items.push(enriched);
        this.byId.set(enriched.id, enriched);

        if (enriched.type === 'music') {
          this.music.push(enriched);
          const moodList = this.musicByMood.get(enriched.mood) || [];
          moodList.push(enriched);
          this.musicByMood.set(enriched.mood, moodList);
        } else if (enriched.type === 'sfx') {
          this.sfx.push(enriched);
          const catList = this.sfxByCategory.get(enriched.category) || [];
          catList.push(enriched);
          this.sfxByCategory.set(enriched.category, catList);
        }

        // Build token index
        const tokens = this._tokenize(
          `${enriched.name} ${enriched.category} ${enriched.mood || ''} ${enriched.tags.join(' ')}`
        );
        for (const tok of tokens) {
          let set = this.tokenIndex.get(tok);
          if (!set) {
            set = new Set();
            this.tokenIndex.set(tok, set);
          }
          set.add(enriched);
        }
      }

      console.info(`[AudioCatalogue] Loaded ${this.items.length} items (${this.music.length} music, ${this.sfx.length} SFX)`);
    } catch (err) {
      console.error(`[AudioCatalogue] Failed to load catalogue:`, err.message);
    }
  }

  _tokenize(text) {
    if (!text) return [];
    return String(text)
      .toLowerCase()
      .replace(/[^a-z0-9_\-\s]/g, ' ')
      .split(/\s+/)
      .filter(t => t.length >= 2);
  }

  _scoreItem(item, queryTokens, targetMoodOrCat, targetDurationS) {
    let score = 0;
    const cat = item.category.toLowerCase();
    const mood = (item.mood || '').toLowerCase();

    if (targetMoodOrCat) {
      const target = targetMoodOrCat.toLowerCase();
      if (cat === target || mood === target) {
        score += 60;
      }
    }

    const itemTokens = new Set(this._tokenize(`${item.name} ${cat} ${mood} ${item.tags.join(' ')}`));

    for (const q of queryTokens) {
      if (cat === q || mood === q) {
        score += 40;
      } else if (item.tags.includes(q)) {
        score += 25;
      } else if (itemTokens.has(q)) {
        score += 15;
      } else {
        // partial substring
        for (const t of itemTokens) {
          if (t.includes(q) || q.includes(t)) {
            score += 5;
            break;
          }
        }
      }
    }

    // Duration suitability bonus for music
    if (item.type === 'music' && targetDurationS && targetDurationS > 0) {
      const dur = item.duration_s || 0;
      if (dur >= targetDurationS) {
        // Track covers entire video! Closer to target without falling short is best.
        const excessRatio = (dur - targetDurationS) / dur;
        score += Math.max(0, 20 - Math.round(excessRatio * 15));
      } else {
        // Track is shorter than video, will need looping
        score += Math.max(0, 10 - Math.round(((targetDurationS - dur) / targetDurationS) * 10));
      }
    }

    return score;
  }

  search({ type, mood, category, query = '', minDurationS, maxDurationS, limit = 5 }) {
    let pool = this.items;
    if (type === 'music') pool = this.music;
    else if (type === 'sfx') pool = this.sfx;

    if (mood) {
      pool = pool.filter(i => (i.mood || i.category) === mood);
    }
    if (category) {
      pool = pool.filter(i => i.category === category);
    }
    if (minDurationS !== undefined) {
      pool = pool.filter(i => (i.duration_s || 0) >= minDurationS);
    }
    if (maxDurationS !== undefined) {
      pool = pool.filter(i => (i.duration_s || 0) <= maxDurationS);
    }

    const queryTokens = this._tokenize(query);
    const targetCat = mood || category;

    if (queryTokens.length === 0 && !targetCat) {
      return pool.slice(0, limit);
    }

    const scored = pool.map(item => ({
      item,
      score: this._scoreItem(item, queryTokens, targetCat, undefined)
    }));

    scored.sort((a, b) => b.score - a.score);
    return scored.slice(0, limit).map(s => s.item);
  }

  findBestMusicBed({ query = '', mood, targetDurationMs }) {
    const targetDurationS = targetDurationMs ? targetDurationMs / 1000 : 30;

    // Detect mood from query if not explicitly passed
    let detectedMood = mood;
    if (!detectedMood && query) {
      const qLower = query.toLowerCase();
      for (const [m, keywords] of Object.entries(MOOD_KEYWORDS)) {
        if (keywords.some(k => qLower.includes(k))) {
          detectedMood = m;
          break;
        }
      }
    }

    let candidates = detectedMood && this.musicByMood.has(detectedMood)
      ? this.musicByMood.get(detectedMood)
      : this.music;

    if (!candidates || candidates.length === 0) {
      candidates = this.music;
    }

    const queryTokens = this._tokenize(query);
    const scored = candidates.map(item => ({
      item,
      score: this._scoreItem(item, queryTokens, detectedMood, targetDurationS)
    }));

    scored.sort((a, b) => b.score - a.score);
    return scored[0]?.item || this.music[0];
  }

  findBestSfx({ query = '', category }) {
    let detectedCategory = category;
    if (!detectedCategory && query) {
      const qLower = query.toLowerCase();
      for (const [c, keywords] of Object.entries(SFX_KEYWORDS)) {
        if (keywords.some(k => qLower.includes(k))) {
          detectedCategory = c;
          break;
        }
      }
    }

    let candidates = detectedCategory && this.sfxByCategory.has(detectedCategory)
      ? this.sfxByCategory.get(detectedCategory)
      : this.sfx;

    if (!candidates || candidates.length === 0) {
      candidates = this.sfx;
    }

    const queryTokens = this._tokenize(query);
    const scored = candidates.map(item => ({
      item,
      score: this._scoreItem(item, queryTokens, detectedCategory, undefined)
    }));

    scored.sort((a, b) => b.score - a.score);
    return scored[0]?.item || this.sfx[0];
  }

  resolveAudioSource(src, context = {}) {
    if (!src || typeof src !== 'string') return null;

    // Already a complete URL
    if (src.startsWith('http://') || src.startsWith('https://') || src.startsWith('data:')) {
      return src;
    }

    // Direct ID match (e.g. fs659451)
    if (this.byId.has(src)) {
      return this.byId.get(src).url;
    }

    // Shorthand formats:
    // catalogue:music:<mood> or catalogue:sfx:<category> or catalogue:<id> or catalogue:<query>
    if (src.startsWith('catalogue:') || src.startsWith('catalog:')) {
      const rest = src.replace(/^catalog(ue)?:\/?\/?/, '').trim();
      const parts = rest.split(':');

      if (parts.length >= 2) {
        const type = parts[0].toLowerCase();
        const identifier = parts[1].toLowerCase();

        if (type === 'music') {
          const match = this.findBestMusicBed({ mood: identifier, query: parts.slice(2).join(' '), targetDurationMs: context.durationMs });
          return match?.url || null;
        }
        if (type === 'sfx') {
          const match = this.findBestSfx({ category: identifier, query: parts.slice(2).join(' ') });
          return match?.url || null;
        }
      }

      // Single identifier after catalogue:
      if (this.byId.has(rest)) {
        return this.byId.get(rest).url;
      }

      // Check if it's a known mood or category
      if (this.musicByMood.has(rest)) {
        const match = this.findBestMusicBed({ mood: rest, targetDurationMs: context.durationMs });
        return match?.url || null;
      }
      if (this.sfxByCategory.has(rest)) {
        const match = this.findBestSfx({ category: rest });
        return match?.url || null;
      }

      // General search fallback
      const searchResults = this.search({ query: rest, limit: 1 });
      return searchResults[0]?.url || null;
    }

    // If starts with /v1/audio/assets or /assets/audio, prepend origin if baseUrl is absolute
    if (src.startsWith('/')) {
      if (this.baseUrl.startsWith('http://') || this.baseUrl.startsWith('https://')) {
        const origin = new URL(this.baseUrl).origin;
        return `${origin}${src}`;
      }
      return src;
    }

    // Fallback: check if src is a known mood or category
    if (this.musicByMood.has(src)) {
      return this.findBestMusicBed({ mood: src, targetDurationMs: context.durationMs })?.url || null;
    }
    if (this.sfxByCategory.has(src)) {
      return this.findBestSfx({ category: src })?.url || null;
    }

    return src;
  }

  // Pre-flight context injection: finds top 3-5 candidates for audio intent in prompt
  getPreflightCandidates(userText = '', targetDurationMs = 30000) {
    if (!userText || typeof userText !== 'string') return [];

    const textLower = userText.toLowerCase();

    // Check for music signals
    const hasMusicSignal = /(music|song|track|soundtrack|beat|lofi|lo-fi|bgm|vibe|melody|acoustic|ambient|electronic|tune|audio bed)/i.test(textLower);
    // Check for SFX signals
    const hasSfxSignal = /(sfx|sound effect|sound fx|whoosh|swipe|pop|click|ding|chime|impact|boom|riser|stinger|glitch|sparkle|shutter|buzzer|applause|cheer|laugh|cartoon)/i.test(textLower);

    if (!hasMusicSignal && !hasSfxSignal) {
      return [];
    }

    const results = [];

    if (hasMusicSignal) {
      const bestMusic = this.findBestMusicBed({ query: userText, targetDurationMs });
      if (bestMusic) results.push(bestMusic);

      // Add a 2nd alternative music track
      const altMusic = this.search({
        type: 'music',
        mood: bestMusic?.mood,
        query: userText,
        limit: 3
      }).filter(m => m.id !== bestMusic?.id);

      if (altMusic[0]) results.push(altMusic[0]);
    }

    if (hasSfxSignal) {
      const bestSfx = this.findBestSfx({ query: userText });
      if (bestSfx && !results.some(r => r.id === bestSfx.id)) {
        results.push(bestSfx);
      }

      // Add 1-2 additional SFX matching other keywords
      const altSfx = this.search({
        type: 'sfx',
        query: userText,
        limit: 3
      }).filter(s => !results.some(r => r.id === s.id));

      for (const s of altSfx.slice(0, 2)) {
        results.push(s);
      }
    }

    return results;
  }

  // Backwards-compatible drop-in for old AudioGeneration (No AI generation - 100% Catalogue)
  async generateBgm(prompt, durationSecs, isEffect = false, chatId = null) {
    console.info(`[AudioCatalogue] Resolving BGM from catalogue for prompt: "${prompt}", duration: ${durationSecs}s, isEffect: ${isEffect}`);
    const durationMs = (durationSecs || 30) * 1000;

    if (isEffect) {
      const item = this.findBestSfx({ query: prompt, category: 'stinger' });
      return {
        url: item.url,
        id: item.id,
        name: item.name,
        duration: item.duration_s,
        category: item.category,
        source: 'catalogue'
      };
    }

    const item = this.findBestMusicBed({ query: prompt, targetDurationMs: durationMs });
    return {
      url: item.url,
      id: item.id,
      name: item.name,
      duration: item.duration_s,
      mood: item.mood,
      source: 'catalogue'
    };
  }

  async fetchSfx(prompt) {
    console.info(`[AudioCatalogue] Resolving SFX from catalogue for prompt: "${prompt}"`);
    const item = this.findBestSfx({ query: prompt });
    return {
      url: item.url,
      id: item.id,
      name: item.name,
      duration: item.duration_s,
      category: item.category,
      source: 'catalogue'
    };
  }

  // HTTP Streaming handler with Range requests for MP3 playback
  serveAudioFile(relPath, req, res) {
    // Sanitize path against directory traversal
    const safeRelPath = path.normalize(relPath).replace(/^(\.\.[\/\\])+/, '');
    const filePath = path.join(this.assetsDir, safeRelPath);

    if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Audio file not found', code: 'not_found' }));
      return;
    }

    const stat = fs.statSync(filePath);
    const fileSize = stat.size;
    const range = req.headers.range;

    const baseHeaders = {
      'Content-Type': 'audio/mpeg',
      'Accept-Ranges': 'bytes',
      'Cache-Control': 'public, max-age=31536000, immutable',
      'Access-Control-Allow-Origin': '*'
    };

    if (range) {
      const parts = range.replace(/bytes=/, '').split('-');
      const start = parseInt(parts[0], 10);
      const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;

      if (start >= fileSize || end >= fileSize) {
        res.writeHead(416, {
          'Content-Range': `bytes */${fileSize}`,
          'Content-Type': 'audio/mpeg'
        });
        res.end();
        return;
      }

      const chunkSize = (end - start) + 1;
      const fileStream = fs.createReadStream(filePath, { start, end });

      res.writeHead(206, {
        ...baseHeaders,
        'Content-Range': `bytes ${start}-${end}/${fileSize}`,
        'Content-Length': chunkSize
      });
      fileStream.pipe(res);
    } else {
      res.writeHead(200, {
        ...baseHeaders,
        'Content-Length': fileSize
      });
      fs.createReadStream(filePath).pipe(res);
    }
  }
}

export {
  AudioCatalogueService,
  MUSIC_MOODS,
  SFX_CATEGORIES,
  MOOD_KEYWORDS,
  SFX_KEYWORDS
};
