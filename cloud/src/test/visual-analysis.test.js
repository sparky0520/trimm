import test from 'node:test';
import assert from 'node:assert/strict';
import { VisualAnalysisService } from '../services/visual-analysis.js';

test('VisualAnalysisService returns empty object for empty or missing sources', async () => {
  const service = new VisualAnalysisService();
  const resEmpty = await service.analyzeSources([]);
  assert.deepEqual(resEmpty, {});

  const resNull = await service.analyzeSources(null);
  assert.deepEqual(resNull, {});
});

test('VisualAnalysisService generates valid fallback profile with required film grammar fields', async () => {
  const service = new VisualAnalysisService();
  const sources = [
    { fingerprint: 'fp-source-1', durationMs: 6500, frames: [] }
  ];

  const results = await service.analyzeSources(sources);
  assert.ok(results['fp-source-1']);
  const profile = results['fp-source-1'];

  assert.equal(typeof profile.summary, 'string');
  assert.equal(profile.shotScale, 'medium');
  assert.equal(profile.setting, 'other');
  assert.equal(profile.cameraMotion, 'handheld_smooth');
  assert.ok(Array.isArray(profile.subjects));
  assert.ok(Array.isArray(profile.narrativeTags));
  assert.equal(profile.usableRange.startMs, 500);
  assert.equal(profile.usableRange.endMs, 6500);
});

test('VisualAnalysisService enforces Analyze Once principle via fingerprint caching', async () => {
  const service = new VisualAnalysisService();
  const sources = [
    { fingerprint: 'fp-tree-clip', durationMs: 4000, frames: [] }
  ];

  const firstPass = await service.analyzeSources(sources);
  assert.ok(firstPass['fp-tree-clip']);

  // Mutate cache entry to verify second pass hits cache directly
  firstPass['fp-tree-clip'].summary = 'Cached custom analysis';

  const secondPass = await service.analyzeSources(sources);
  assert.equal(secondPass['fp-tree-clip'].summary, 'Cached custom analysis');
});

test('VisualAnalysisService parses mock vision model response conforming to structured JSON schema', async () => {
  const service = new VisualAnalysisService({ apiKey: 'mock-key' });
  
  // Mock the OpenAI client chat completion
  service.client = {
    chat: {
      completions: {
        create: async () => ({
          choices: [
            {
              message: {
                content: JSON.stringify({
                  summary: 'Wide shot of lush green park with gentle breeze',
                  setting: 'exterior_daylight',
                  shotScale: 'wide',
                  subjects: ['trees', 'park bench'],
                  activity: 'leaves rustling',
                  cameraMotion: 'static',
                  usableRange: { startMs: 0, endMs: 5000 },
                  narrativeTags: ['establishing', 'nature', 'calm']
                })
              }
            }
          ]
        })
      }
    }
  };

  const sources = [
    {
      fingerprint: 'fp-park-mock',
      durationMs: 5000,
      frames: [
        { atMs: 1000, base64: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=' }
      ]
    }
  ];

  const results = await service.analyzeSources(sources);
  const profile = results['fp-park-mock'];
  assert.ok(profile);
  assert.equal(profile.summary, 'Wide shot of lush green park with gentle breeze');
  assert.equal(profile.shotScale, 'wide');
  assert.equal(profile.setting, 'exterior_daylight');
  assert.deepEqual(profile.narrativeTags, ['establishing', 'nature', 'calm']);
  assert.equal(profile.usableRange.startMs, 0);
  assert.equal(profile.usableRange.endMs, 5000);
});

test('VisualAnalysisService defaults to gemini-flash-latest and Google OpenAI-compatible endpoint', () => {
  const service = new VisualAnalysisService();
  assert.equal(service.model, 'gemini-flash-latest');
  assert.equal(service.baseURL, 'https://generativelanguage.googleapis.com/v1beta/openai/');
});

test('VisualAnalysisService strips markdown code fences when parsing model responses', async () => {
  const service = new VisualAnalysisService({ apiKey: 'mock-key' });
  service.client = {
    chat: {
      completions: {
        create: async () => ({
          choices: [
            {
              message: {
                content: '```json\n{"summary": "Markdown wrapped clip", "setting": "interior_home", "shotScale": "close_up"}\n```'
              }
            }
          ]
        })
      }
    }
  };

  const sources = [
    {
      fingerprint: 'fp-markdown-test',
      durationMs: 3000,
      frames: [{ atMs: 500, base64: 'abc' }]
    }
  ];

  const results = await service.analyzeSources(sources);
  assert.ok(results['fp-markdown-test']);
  assert.equal(results['fp-markdown-test'].summary, 'Markdown wrapped clip');
  assert.equal(results['fp-markdown-test'].setting, 'interior_home');
  assert.equal(results['fp-markdown-test'].shotScale, 'close_up');
});

test('VisualAnalysisService listAvailableModels discovers models via client.models.list', async () => {
  const mockModels = [{ id: 'gemini-1.5-flash' }, { id: 'gemini-1.5-pro' }];
  const service = new VisualAnalysisService({ apiKey: 'mock-key' });
  service.client = {
    models: {
      list: async () => ({ data: mockModels })
    }
  };

  const models = await service.listAvailableModels();
  assert.deepEqual(models, ['gemini-1.5-flash', 'gemini-1.5-pro']);
});

