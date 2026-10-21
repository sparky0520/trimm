import test from 'node:test';
import assert from 'node:assert/strict';
import { AudioCatalogueService, MUSIC_MOODS, SFX_CATEGORIES } from '../services/audio-catalogue.js';
import { getAgentTools, SEARCH_AUDIO_CATALOGUE_TOOL } from '../schema/agent-decision-schema.js';
import { mergeSchemaPatch } from '../services/openai-agent.js';

test('AudioCatalogueService loads complete 531-item library', () => {
  const service = new AudioCatalogueService();
  assert.equal(service.items.length, 531, 'Catalogue must contain exactly 531 items');
  assert.equal(service.music.length, 134, 'Must have 134 music tracks');
  assert.equal(service.sfx.length, 397, 'Must have 397 sound effects');
  assert.equal(service.byId.size, 531, 'All items must be keyed in byId map');
});

test('AudioCatalogueService indexes all 14 music moods and 29 SFX categories', () => {
  const service = new AudioCatalogueService();

  assert.equal(MUSIC_MOODS.length, 14, 'Should declare 14 music moods');
  for (const mood of MUSIC_MOODS) {
    assert.ok(service.musicByMood.has(mood), `Mood ${mood} should have tracks indexed`);
    assert.ok(service.musicByMood.get(mood).length > 0, `Mood ${mood} should have at least 1 track`);
  }

  for (const cat of SFX_CATEGORIES) {
    assert.ok(service.sfxByCategory.has(cat), `SFX category ${cat} should have clips indexed`);
    assert.ok(service.sfxByCategory.get(cat).length > 0, `Category ${cat} should have at least 1 clip`);
  }
});

test('search filters by type, mood, category, and keyword', () => {
  const service = new AudioCatalogueService();

  const lofiResults = service.search({ type: 'music', mood: 'chill_lofi', limit: 5 });
  assert.ok(lofiResults.length > 0, 'Should find chill_lofi tracks');
  assert.ok(lofiResults.every(r => r.type === 'music' && r.mood === 'chill_lofi'));

  const whooshResults = service.search({ type: 'sfx', category: 'whoosh', limit: 5 });
  assert.ok(whooshResults.length > 0, 'Should find whoosh clips');
  assert.ok(whooshResults.every(r => r.type === 'sfx' && r.category === 'whoosh'));

  const keywordResults = service.search({ query: 'piano', limit: 3 });
  assert.ok(keywordResults.length > 0, 'Should find piano results');
  assert.ok(keywordResults.some(r => r.name.toLowerCase().includes('piano') || r.tags.includes('piano')));
});

test('findBestMusicBed selects best track with duration matching', () => {
  const service = new AudioCatalogueService();

  const musicBed = service.findBestMusicBed({
    query: 'chill lofi study beat',
    targetDurationMs: 45000
  });

  assert.ok(musicBed, 'Must return a music bed');
  assert.equal(musicBed.type, 'music');
  assert.equal(musicBed.mood, 'chill_lofi');
  assert.ok(musicBed.url.includes('/music/chill_lofi/'));
  assert.ok(musicBed.duration_s > 0);
});

test('findBestSfx selects best sound effect for cut or transition', () => {
  const service = new AudioCatalogueService();

  const sfx = service.findBestSfx({ query: 'fast whoosh transition' });
  assert.ok(sfx, 'Must return an SFX');
  assert.equal(sfx.type, 'sfx');
  assert.equal(sfx.category, 'whoosh');
  assert.ok(sfx.url.includes('/sfx/whoosh/'));
});

test('resolveAudioSource handles URLs, IDs, shorthands, and fallback search', () => {
  const service = new AudioCatalogueService();

  // Full HTTP URL
  const httpUrl = 'https://cdn.example.com/audio/track.mp3';
  assert.equal(service.resolveAudioSource(httpUrl), httpUrl);

  // Exact ID
  const sampleItem = service.items[0];
  assert.equal(service.resolveAudioSource(sampleItem.id), sampleItem.url);

  // Shorthand: catalogue:music:chill_lofi
  const lofiUrl = service.resolveAudioSource('catalogue:music:chill_lofi');
  assert.ok(lofiUrl && lofiUrl.includes('/music/chill_lofi/'));

  // Shorthand: catalogue:sfx:whoosh
  const whooshUrl = service.resolveAudioSource('catalogue:sfx:whoosh');
  assert.ok(whooshUrl && whooshUrl.includes('/sfx/whoosh/'));

  // General search shorthand: catalogue:relaxing guitar
  const guitarUrl = service.resolveAudioSource('catalogue:relaxing guitar');
  assert.ok(guitarUrl && guitarUrl.includes('.mp3'));
});

test('getPreflightCandidates detects audio signals and returns candidate assets', () => {
  const service = new AudioCatalogueService();

  const withAudio = service.getPreflightCandidates('Can you add relaxing lofi music and a whoosh between cuts?', 30000);
  assert.ok(withAudio.length >= 2, 'Should return both music and sfx candidates');
  assert.ok(withAudio.some(c => c.type === 'music'));
  assert.ok(withAudio.some(c => c.type === 'sfx'));

  const withoutAudio = service.getPreflightCandidates('Cut the silences and trim the beginning by 2 seconds.', 30000);
  assert.equal(withoutAudio.length, 0, 'Should return empty array when no audio intent');
});

test('generateBgm and fetchSfx resolve strictly from catalogue with no AI generation', async () => {
  const service = new AudioCatalogueService();

  const bgm = await service.generateBgm('chill acoustic background for vlog', 30);
  assert.equal(bgm.source, 'catalogue');
  assert.ok(bgm.url.endsWith('.mp3'));
  assert.ok(bgm.duration > 0);
  assert.ok(bgm.id.startsWith('fs'));

  const sfx = await service.fetchSfx('camera shutter');
  assert.equal(sfx.source, 'catalogue');
  assert.ok(sfx.url.endsWith('.mp3'));
  assert.ok(sfx.duration > 0);
  assert.equal(sfx.category, 'shutter');
});

test('mergeSchemaPatch heals AudioNode: resolves src, defaults volume and autoDuck', () => {
  const service = new AudioCatalogueService();

  const baseSchema = {
    settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: false },
    timeline: [
      { type: 'video', id: 'v1', src: 'local://video.mp4', startMs: 0, endMs: 20000 }
    ]
  };

  const patchSchema = {
    settings: {},
    timeline: [
      {
        type: 'audio',
        id: 'bgm-1',
        src: 'catalogue:music:chill_lofi',
        startMs: 0,
        endMs: 20000
      },
      {
        type: 'audio',
        id: 'sfx-1',
        src: 'catalogue:sfx:whoosh',
        startMs: 5000,
        endMs: 5800
      }
    ]
  };

  const merged = mergeSchemaPatch(baseSchema, patchSchema, [{ uri: 'local://video.mp4' }], [], service);
  const audioNodes = merged.timeline.filter(n => n.type === 'audio');
  assert.equal(audioNodes.length, 2);

  const bgmNode = audioNodes.find(n => n.id === 'bgm-1');
  assert.ok(bgmNode.src.includes('/music/chill_lofi/'), 'BGM shorthand must be resolved to asset URL');
  assert.equal(bgmNode.volume, 0.25, 'BGM volume should default to 0.25');
  assert.equal(bgmNode.autoDuck, true, 'BGM autoDuck should default to true');

  const sfxNode = audioNodes.find(n => n.id === 'sfx-1');
  assert.ok(sfxNode.src.includes('/sfx/whoosh/'), 'SFX shorthand must be resolved to asset URL');
  assert.equal(sfxNode.volume, 0.8, 'SFX volume should default to 0.8');
  assert.equal(sfxNode.autoDuck, false, 'SFX autoDuck should default to false');
});

test('SEARCH_AUDIO_CATALOGUE_TOOL is registered in getAgentTools', () => {
  const tools = getAgentTools();
  const searchTool = tools.find(t => t.name === 'search_audio_catalogue');
  assert.ok(searchTool, 'search_audio_catalogue tool must be present in agent tools');
  assert.equal(searchTool.parameters.required[0], 'type');
  assert.ok(searchTool.parameters.properties.mood);
  assert.ok(searchTool.parameters.properties.category);
});
