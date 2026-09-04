
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';
import { TranscriptionUploads } from '../services/transcription-uploads.js';

function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'trimm-upload-test-'));
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

test('failed chunks roll back partial bytes so retry offsets remain safe', async (t) => {
  const { directory, uploads } = fixture(); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const upload = uploads.create({ contentType: 'video/mp4', size: 4, owner: 'a' });
  await assert.rejects(uploads.append(upload.id, 'a', 0, Readable.from('abcde')), /declared upload size/);
  assert.equal(uploads.read(upload.id, 'a').offset, 0);
  assert.equal(fs.statSync(uploads.mediaPath(upload.id)).size, 0);
  assert.equal((await uploads.append(upload.id, 'a', 0, Readable.from('abcd'))).offset, 4);
});

test('cleanup removes expired, malformed, and unreferenced upload objects', (t) => {
  const { directory, uploads } = fixture(); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const expired = uploads.create({ contentType: 'audio/mpeg', size: 2, owner: 'a' });
  const metadata = uploads.read(expired.id, 'a');
  fs.writeFileSync(uploads.metadataPath(expired.id), JSON.stringify({ ...metadata, updatedAt: Date.now() - 2000 }));
  fs.writeFileSync(path.join(directory, 'malformed.json'), '{');
  fs.writeFileSync(path.join(directory, 'orphan.media'), 'bytes');
  uploads.cleanup();
  assert.equal(fs.readdirSync(directory).length, 0);
});
