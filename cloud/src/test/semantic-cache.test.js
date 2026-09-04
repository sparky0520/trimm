
import test from 'node:test';
import assert from 'node:assert/strict';
import { VolatileSemanticCache } from '../services/semantic-cache.js';

function entry(overrides = {}) {
  return {
    ownerId: 'user-a',
    projectId: 'project-a',
    cacheType: 'media_index',
    sourceFingerprint: 'a'.repeat(64),
    rangeStartMs: 1000,
    rangeEndMs: 2000,
    transform: { height: 720, kind: 'frames' },
    model: 'gemini',
    modelVersion: '3.6-flash',
    analyzerVersion: 'index-v2',
    value: { observations: [{ timestampMs: 1200, label: 'screen' }] },
    provenance: { derivativeJobIds: ['job-a'], interpretation: true },
    costMicros: 25,
    expiresAt: 20_000,
    ...overrides,
  };
}

test('cache survives a service restart and records reuse cost metrics', async () => {
  const records = new Map();
  const firstProcess = new VolatileSemanticCache({ records, now: () => 10_000 });
  await firstProcess.put(entry());
  const restartedProcess = new VolatileSemanticCache({ records, now: () => 11_000 });
  const hit = await restartedProcess.get(entry());
  assert.deepEqual(hit.value.observations, [{ timestampMs: 1200, label: 'screen' }]);
  assert.equal(hit.hitCount, 1);
  assert.equal(hit.costMicros, 25);
  assert.equal(hit.provenance.interpretation, true);
});

test('cache reuse requires owner, exact range, transform, model and analyzer versions', async () => {
  const cache = new VolatileSemanticCache({ now: () => 10_000 });
  await cache.put(entry());
  for (const mismatch of [
    { ownerId: 'user-b' },
    { rangeStartMs: 1001 },
    { transform: { height: 1080, kind: 'frames' } },
    { modelVersion: '3.7-flash' },
    { analyzerVersion: 'index-v3' },
  ]) assert.equal(await cache.get(entry(mismatch)), null);
});

test('source changes invalidate only overlapping ranges and owner deletion is immediate', async () => {
  const cache = new VolatileSemanticCache({ now: () => 10_000 });
  await cache.put(entry());
  await cache.put(entry({ rangeStartMs: 3000, rangeEndMs: 4000 }));
  assert.equal(await cache.invalidateRange({
    ownerId: 'user-a', projectId: 'project-a', sourceFingerprint: 'a'.repeat(64),
    rangeStartMs: 1500, rangeEndMs: 2500,
  }), 1);
  assert.equal(await cache.get(entry()), null);
  assert.ok(await cache.get(entry({ rangeStartMs: 3000, rangeEndMs: 4000 })));
  assert.equal(await cache.deleteOwner('user-a'), 1);
  assert.equal(await cache.get(entry({ rangeStartMs: 3000, rangeEndMs: 4000 })), null);
});

test('provider context and media indexes use separate cache namespaces', async () => {
  const cache = new VolatileSemanticCache({ now: () => 10_000 });
  await cache.put(entry({ cacheType: 'provider_context', value: { providerHandle: 'cached-1' } }));
  assert.equal(await cache.get(entry()), null);
  assert.equal((await cache.get(entry({ cacheType: 'provider_context' }))).value.providerHandle, 'cached-1');
});

test('expired cache entries are not reused and retention cleanup removes them', async () => {
  let now = 10_000;
  const cache = new VolatileSemanticCache({ now: () => now });
  await cache.put(entry({ expiresAt: 10_001 }));
  now = 10_002;
  assert.equal(await cache.get(entry({ expiresAt: 10_001 })), null);
  assert.equal(await cache.cleanupExpired(), 1);
});
