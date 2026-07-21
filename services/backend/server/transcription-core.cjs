'use strict';

const { AssemblyAI } = require('assemblyai');
const { Transform } = require('node:stream');

const DEFAULT_MAX_BYTES = 1024 * 1024 * 1024;

function validateUpload(headers, maxBytes = DEFAULT_MAX_BYTES) {
  const contentType = String(headers['content-type'] || '').toLowerCase().split(';')[0];
  if (!(contentType.startsWith('audio/') || contentType.startsWith('video/') || contentType === 'application/octet-stream')) {
    throw Object.assign(new Error('An audio or video request body is required.'), { status: 415 });
  }
  const length = Number(headers['content-length']);
  if (Number.isFinite(length) && (length <= 0 || length > maxBytes)) {
    throw Object.assign(new Error('Media upload exceeds the configured limit.'), { status: 413 });
  }
}

function normalizeTranscript(transcript) {
  if (transcript?.status === 'error') throw Object.assign(new Error(transcript.error || 'AssemblyAI transcription failed.'), { status: 502 });
  return {
    id: String(transcript?.id || ''),
    languageCode: transcript?.language_code || null,
    words: (transcript?.words || []).map((word) => ({
      text: String(word.text || '').trim().slice(0, 120),
      startMs: Math.max(0, Math.trunc(Number(word.start) || 0)),
      endMs: Math.max(0, Math.trunc(Number(word.end) || 0)),
      confidence: Math.max(0, Math.min(1, Number(word.confidence) || 0)),
    })).filter((word) => word.text && word.endMs >= word.startMs),
  };
}

function boundedStream(stream, maxBytes = DEFAULT_MAX_BYTES) {
  let received = 0;
  const limiter = new Transform({
    transform(chunk, _encoding, callback) {
      received += chunk.length;
      if (received > maxBytes) return callback(Object.assign(new Error('Media upload exceeds the configured limit.'), { status: 413 }));
      callback(null, chunk);
    },
  });
  stream.on('error', (error) => limiter.destroy(error));
  return stream.pipe(limiter);
}

async function transcribe(stream, options = {}) {
  if (!options.apiKey && !process.env.ASSEMBLYAI_API_KEY) throw Object.assign(new Error('ASSEMBLYAI_API_KEY is not configured'), { status: 503 });
  const client = options.client || new AssemblyAI({ apiKey: options.apiKey || process.env.ASSEMBLYAI_API_KEY });
  const transcript = await client.transcripts.transcribe({
    audio: options.skipStreamLimit ? stream : boundedStream(stream, options.maxBytes),
    speech_models: [process.env.ASSEMBLYAI_SPEECH_MODEL || 'universal-2'],
    language_detection: true,
    format_text: true,
  });
  return normalizeTranscript(transcript);
}

module.exports = { DEFAULT_MAX_BYTES, boundedStream, normalizeTranscript, transcribe, validateUpload };
