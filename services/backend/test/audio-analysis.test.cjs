'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  ANALYZER_VERSION, duplicateOf, invalidateDerivedDescriptors, normalizeAnalysis, previewNormalization,
  tempoFromBeats,
} = require('../server/audio-analysis.cjs');

const checksum = 'a'.repeat(64);

test('normalizes searchable audio descriptors deterministically', () => {
  const analysis = normalizeAnalysis({
    durationMs: 12_345.4, integratedLufs: -22.1, truePeakDbtp: -2.5,
    bpm: 121.8, beatConfidence: 1.4,
    sections: [{ label: 'intro', startMs: 0, endMs: 3000, confidence: .8 }],
    energyCurve: [{ atMs: 2000, energy: .7 }, { atMs: 0, energy: -.2 }],
    tags: [' Warm ', 'corporate', 'warm'], acousticFingerprint: 'acoustic-1',
  }, checksum, '2026-07-27T00:00:00.000Z');
  assert.equal(analysis.analyzerVersion, ANALYZER_VERSION);
  assert.equal(analysis.durationMs, 12345);
  assert.equal(analysis.beatConfidence, 1);
  assert.deepEqual(analysis.tags, ['corporate', 'warm']);
  assert.deepEqual(analysis.energyCurve.map((point) => point.atMs), [0, 2000]);
});

test('derives BPM and confidence from stable detected beat timestamps', () => {
  const tempo = tempoFromBeats('0.5\n1.0\n1.5\n2.0\n2.5\n');
  assert.equal(tempo.bpm, 120);
  assert.equal(tempo.beatConfidence, 1);
});

test('preview gain is non-destructive and respects the true-peak ceiling', () => {
  const analysis = normalizeAnalysis({ durationMs: 1000, integratedLufs: -24, truePeakDbtp: -3 }, checksum);
  assert.deepEqual(previewNormalization(analysis), {
    gainDb: 2, targetLufs: -16, peakCeilingDbtp: -1, reason: 'true-peak-limited',
  });
  assert.equal(analysis.integratedLufs, -24);
});

test('duplicates match exact bytes or an acoustic fingerprint', () => {
  const catalog = [
    { id: 'exact', checksum, analysis: { acousticFingerprint: 'one' } },
    { id: 'transcode', checksum: 'b'.repeat(64), analysis: { acousticFingerprint: 'same-audio' } },
  ];
  assert.equal(duplicateOf({ id: 'new', checksum }, catalog).id, 'exact');
  assert.equal(duplicateOf({
    id: 'new', checksum: 'c'.repeat(64), analysis: { acousticFingerprint: 'same-audio' },
  }, catalog).id, 'transcode');
});

test('analyzer invalidation removes only derived descriptors and preserves rights provenance', () => {
  const asset = {
    id: 'track', checksum, licenseId: 'CC0-1.0', attribution: 'Artist',
    rightsArtifact: { path: 'receipt.pdf' }, analysis: { analyzerVersion: 'old' },
    preview: { gainDb: 3 }, duplicateOf: 'other',
  };
  const invalidated = invalidateDerivedDescriptors(asset);
  assert.equal(invalidated.analysis, null);
  assert.equal(invalidated.licenseId, asset.licenseId);
  assert.equal(invalidated.rightsArtifact, asset.rightsArtifact);
  assert.equal('preview' in invalidated, false);
});
