'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { normalizeTranscript, transcribe, validateUpload } = require('../server/transcription-core.cjs');

test('mobile transcription accepts only bounded audio and video bodies', () => {
  assert.doesNotThrow(() => validateUpload({ 'content-type': 'video/mp4', 'content-length': '1000' }));
  assert.throws(() => validateUpload({ 'content-type': 'application/json', 'content-length': '1000' }), /audio or video/);
  assert.throws(() => validateUpload({ 'content-type': 'audio/mpeg', 'content-length': '1001' }, 1000), /limit/);
});

test('AssemblyAI transcript normalization returns safe timestamped words', () => {
  assert.deepEqual(normalizeTranscript({ id: 'tx-1', status: 'completed', language_code: 'en', words: [
    { text: ' hello ', start: 12.8, end: 210.9, confidence: 1.5 },
    { text: '', start: 300, end: 400, confidence: .5 },
  ] }), { id: 'tx-1', languageCode: 'en', words: [{ text: 'hello', startMs: 12, endMs: 210, confidence: 1, speakerId: null }] });
  assert.throws(() => normalizeTranscript({ status: 'error', error: 'provider failure' }), /provider failure/);
});

test('transcription sends the media stream only to the server-side AssemblyAI client', async () => {
  const stream = { marker: 'private-media-stream' };
  let request;
  const client = { transcripts: { transcribe: async (value) => { request = value; return { id: 'tx', status: 'completed', words: [{ text: 'Hi', start: 0, end: 100, confidence: .9, speaker: 'A' }] }; } } };
  const result = await transcribe(stream, { apiKey: 'server-only', client, skipStreamLimit: true });
  assert.equal(request.audio, stream);
  assert.deepEqual(request.speech_models, ['universal-2']);
  assert.equal(request.speaker_labels, true);
  assert.deepEqual(result.words, [{ text: 'Hi', startMs: 0, endMs: 100, confidence: .9, speakerId: 'speaker-A' }]);
});

test('Android analysis uses remote transcription before the planner and no Whisper invocation', () => {
  const worker = fs.readFileSync(path.join(__dirname, '..', '..', '..', 'apps/mobile/app/src/main/java/ai/ctrlb/mobile/work/MediaWorkers.kt'), 'utf8');
  assert.match(worker, /BuildConfig\.TRANSCRIPTION_URL\.takeIf\(String::isNotBlank\)/);
  assert.match(worker, /TranscriptionClient\(it,\s*AuthTokenProvider::currentToken\)/);
  assert.doesNotMatch(worker, /BundledWhisperTranscriber|WhisperModelManager/);
  assert.ok(worker.indexOf('client.transcribe') < worker.indexOf('PlannerClient'));
});

test('Android resumable uploads refresh auth and clean up cancelled sessions', () => {
  const client = fs.readFileSync(path.join(__dirname, '..', '..', '..', 'apps/mobile/app/src/main/java/ai/ctrlb/mobile/planner/TranscriptionClient.kt'), 'utf8');
  assert.match(client, /connection\([^)]*,\s*"PUT",\s*accessToken\(\)\)/);
  assert.match(client, /remoteOffset\(id\)/);
  assert.match(client, /withContext\(NonCancellable\).*deleteSession\(session\.id\)/s);
  assert.doesNotMatch(client, /val token = accessToken\(\)/);
});
