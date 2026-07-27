'use strict';

const childProcess = require('node:child_process');
const { promisify } = require('node:util');

const execFile = promisify(childProcess.execFile);
const ANALYZER_VERSION = 'audio-descriptors-v1';
const PREVIEW_TARGET_LUFS = -16;

function finite(value, fallback = null) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value));
}

function normalizeCurve(points, durationMs) {
  return (Array.isArray(points) ? points : []).map((point) => ({
    atMs: clamp(Math.round(finite(point.atMs, 0)), 0, durationMs),
    energy: clamp(finite(point.energy, 0), 0, 1),
  })).sort((left, right) => left.atMs - right.atMs);
}

function normalizeSections(sections, durationMs) {
  const source = Array.isArray(sections) && sections.length
    ? sections
    : [{ label: 'full', startMs: 0, endMs: durationMs, confidence: 0 }];
  return source.map((section, index) => {
    const startMs = clamp(Math.round(finite(section.startMs, 0)), 0, durationMs);
    const endMs = clamp(Math.round(finite(section.endMs, durationMs)), startMs, durationMs);
    return {
      label: String(section.label || `section-${index + 1}`).slice(0, 80),
      startMs, endMs, confidence: clamp(finite(section.confidence, 0), 0, 1),
    };
  });
}

function normalizeAnalysis(raw, checksum, analyzedAt = new Date().toISOString()) {
  const durationMs = Math.max(0, Math.round(finite(raw.durationMs, 0)));
  const integratedLufs = finite(raw.integratedLufs);
  const truePeakDbtp = finite(raw.truePeakDbtp);
  const bpm = finite(raw.bpm);
  return {
    analyzerVersion: ANALYZER_VERSION,
    analyzedAt,
    sourceChecksum: String(checksum || '').toLowerCase(),
    durationMs,
    integratedLufs,
    truePeakDbtp,
    bpm: bpm == null ? null : clamp(bpm, 20, 400),
    beatConfidence: clamp(finite(raw.beatConfidence, 0), 0, 1),
    sections: normalizeSections(raw.sections, durationMs),
    energyCurve: normalizeCurve(raw.energyCurve, durationMs),
    tags: [...new Set((raw.tags || []).map((tag) => String(tag).trim().toLowerCase()).filter(Boolean))].sort(),
    acousticFingerprint: String(raw.acousticFingerprint || ''),
  };
}

function previewNormalization(analysis, targetLufs = PREVIEW_TARGET_LUFS, peakCeilingDbtp = -1) {
  if (!Number.isFinite(analysis?.integratedLufs)) return { gainDb: 0, targetLufs, reason: 'loudness-unavailable' };
  const loudnessGain = targetLufs - analysis.integratedLufs;
  const peakLimitedGain = Number.isFinite(analysis.truePeakDbtp)
    ? peakCeilingDbtp - analysis.truePeakDbtp
    : loudnessGain;
  const gainDb = clamp(Math.min(loudnessGain, peakLimitedGain), -24, 12);
  return {
    gainDb: Math.round(gainDb * 100) / 100, targetLufs, peakCeilingDbtp,
    reason: peakLimitedGain < loudnessGain ? 'true-peak-limited' : 'target-loudness',
  };
}

function duplicateOf(candidate, catalog) {
  return (catalog || []).find((item) =>
    item.id !== candidate.id && (
      item.checksum === candidate.checksum ||
      (candidate.analysis?.acousticFingerprint &&
        item.analysis?.acousticFingerprint === candidate.analysis.acousticFingerprint)
    )) || null;
}

function invalidateDerivedDescriptors(asset, nextAnalyzerVersion = ANALYZER_VERSION) {
  if (!asset.analysis || asset.analysis.analyzerVersion === nextAnalyzerVersion) return asset;
  const { analysis, preview, duplicateOf: duplicateId, ...provenance } = asset;
  return { ...provenance, analysis: null };
}

function parseEbur128(stderr) {
  const summaries = [...String(stderr).matchAll(/I:\s*(-?[\d.]+)\s*LUFS[\s\S]*?Peak:\s*(-?[\d.]+)\s*dBFS/g)];
  const last = summaries.at(-1);
  return { integratedLufs: finite(last?.[1]), truePeakDbtp: finite(last?.[2]) };
}

function parseEnergy(stderr, durationMs) {
  const timestamps = [...String(stderr).matchAll(/pts_time:([\d.]+)[\s\S]*?lavfi\.astats\.Overall\.RMS_level=(-?[\d.]+)/g)];
  return timestamps.map((match) => {
    const db = finite(match[2], -90);
    return { atMs: Math.min(durationMs, Math.round(Number(match[1]) * 1000)), energy: clamp((db + 60) / 60, 0, 1) };
  });
}

function tempoFromBeats(output) {
  const beats = String(output).split(/\r?\n/).map(Number).filter(Number.isFinite);
  const intervals = beats.slice(1).map((beat, index) => beat - beats[index]).filter((gap) => gap > .15 && gap < 3);
  if (intervals.length < 2) return { bpm: null, beatConfidence: 0 };
  const sorted = [...intervals].sort((left, right) => left - right);
  const median = sorted[Math.floor(sorted.length / 2)];
  const deviation = intervals.reduce((sum, gap) => sum + Math.abs(gap - median), 0) / intervals.length;
  return {
    bpm: Math.round((60 / median) * 10) / 10,
    beatConfidence: clamp(1 - deviation / median, 0, 1),
  };
}

function describeEnergy(energyCurve, durationMs) {
  if (!energyCurve.length) return { sections: undefined, tags: [] };
  const sectionCount = Math.min(4, Math.max(1, Math.ceil(durationMs / 30_000)));
  const sections = Array.from({ length: sectionCount }, (_, index) => {
    const startMs = Math.round(durationMs * index / sectionCount);
    const endMs = Math.round(durationMs * (index + 1) / sectionCount);
    const points = energyCurve.filter((point) => point.atMs >= startMs && point.atMs <= endMs);
    const energy = points.length ? points.reduce((sum, point) => sum + point.energy, 0) / points.length : 0;
    return { label: index === 0 ? 'intro' : index === sectionCount - 1 ? 'outro' : `body-${index}`, startMs, endMs, confidence: .5, energy };
  });
  const average = energyCurve.reduce((sum, point) => sum + point.energy, 0) / energyCurve.length;
  return { sections, tags: [average > .67 ? 'high-energy' : average < .33 ? 'low-energy' : 'medium-energy'] };
}

async function commandAnalysis(filePath, options = {}) {
  const run = options.execFile || execFile;
  const ffprobe = await run(options.ffprobePath || 'ffprobe', [
    '-v', 'error', '-show_entries', 'format=duration:format_tags=genre,mood,bpm',
    '-of', 'json', filePath,
  ], { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
  const probe = JSON.parse(ffprobe.stdout);
  const durationMs = Math.round(finite(probe.format?.duration, 0) * 1000);
  const loudness = await run(options.ffmpegPath || 'ffmpeg', [
    '-hide_banner', '-nostats', '-i', filePath, '-filter_complex', 'ebur128=peak=true', '-f', 'null', '-',
  ], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
  const energy = await run(options.ffmpegPath || 'ffmpeg', [
    '-hide_banner', '-nostats', '-i', filePath,
    '-af', 'astats=metadata=1:reset=1,ametadata=print:key=lavfi.astats.Overall.RMS_level',
    '-f', 'null', '-',
  ], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
  const fingerprint = await run(options.fpcalcPath || 'fpcalc', ['-json', filePath], {
    encoding: 'utf8', maxBuffer: 8 * 1024 * 1024,
  });
  const beats = await run(options.aubioPath || 'aubio', ['tempo', filePath], {
    encoding: 'utf8', maxBuffer: 4 * 1024 * 1024,
  });
  const tags = probe.format?.tags || {};
  const energyCurve = parseEnergy(energy.stderr, durationMs);
  const energyDescription = describeEnergy(energyCurve, durationMs);
  const tempo = tempoFromBeats(beats.stdout);
  return {
    durationMs, ...parseEbur128(loudness.stderr), energyCurve, sections: energyDescription.sections,
    bpm: tempo.bpm || tags.bpm, beatConfidence: tempo.bpm ? tempo.beatConfidence : tags.bpm ? 0.5 : 0,
    tags: [...[tags.genre, tags.mood].filter(Boolean).flatMap((value) => String(value).split(/[,;/]/)), ...energyDescription.tags],
    acousticFingerprint: JSON.parse(fingerprint.stdout).fingerprint,
  };
}

async function analyzeAudioAsset(filePath, checksum, options = {}) {
  const raw = options.inspectAudio
    ? await options.inspectAudio(filePath)
    : await commandAnalysis(filePath, options);
  return normalizeAnalysis(raw, checksum, options.analyzedAt);
}

module.exports = {
  ANALYZER_VERSION, PREVIEW_TARGET_LUFS, analyzeAudioAsset, commandAnalysis, duplicateOf,
  invalidateDerivedDescriptors, normalizeAnalysis, previewNormalization, tempoFromBeats,
};
