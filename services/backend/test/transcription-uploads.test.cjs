'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Readable } = require('node:stream');
const { TranscriptionUploads } = require('../server/transcription-uploads.cjs');

function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ctrlb-upload-test-'));
  return { directory, uploads: new TranscriptionUploads({ directory, maxBytes: 32, ttlMs: 1000 }) };
}

test('resumable transcription uploads enforce ordered offsets and declared size', async (t) => {
  const { directory, uploads } = fixture(); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const upload = uploads.create({ contentType: 'video/mp4', size: 6, owner: 'user-a' });
  assert.equal((await uploads.append(upload.id, 'user-a', 0, Readable.from('abc'))).offset, 3);
  await assert.rejects(uploads.append(upload.id, 'user-a', 0, Readable.from('x')), /offset/);
  assert.equal((await uploads.append(upload.id, 'user-a', 3, Readable.from('def'))).offset, 6);
  const complete = uploads.complete(upload.id, 'user-a');
  let body = ''; for await (const chunk of complete.stream) body += chunk;
  assert.equal(body, 'abcdef');
});

test('uploads are owner-scoped, bounded, cancellable, and expire', async (t) => {
  const { directory, uploads } = fixture(); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  assert.throws(() => uploads.create({ contentType: 'text/plain', size: 2, owner: 'a' }), /audio or video/);
  assert.throws(() => uploads.create({ contentType: 'video/mp4', size: 33, owner: 'a' }), /limit/);
  const upload = uploads.create({ contentType: 'audio/mpeg', size: 2, owner: 'a' });
  assert.throws(() => uploads.read(upload.id, 'b'), /not found/);
  uploads.remove(upload.id); assert.throws(() => uploads.read(upload.id, 'a'), /not found/);
});
