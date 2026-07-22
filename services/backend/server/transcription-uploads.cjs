'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Transform } = require('node:stream');
const { DEFAULT_MAX_BYTES } = require('./transcription-core.cjs');

const DEFAULT_TTL_MS = 6 * 60 * 60 * 1000;

class TranscriptionUploads {
  constructor(options = {}) {
    this.directory = options.directory || path.join(os.tmpdir(), 'ctrlb-transcription-uploads');
    this.maxBytes = options.maxBytes || DEFAULT_MAX_BYTES;
    this.ttlMs = options.ttlMs || DEFAULT_TTL_MS;
    fs.mkdirSync(this.directory, { recursive: true });
  }

  metadataPath(id) { return path.join(this.directory, `${id}.json`); }
  mediaPath(id) { return path.join(this.directory, `${id}.media`); }

  create({ contentType, size, owner }) {
    const normalizedSize = Number(size);
    if (!Number.isSafeInteger(normalizedSize) || normalizedSize <= 0 || normalizedSize > this.maxBytes) {
      throw Object.assign(new Error('Media upload exceeds the configured limit.'), { status: 413 });
    }
    if (!/^(audio|video)\//.test(contentType) && contentType !== 'application/octet-stream') {
      throw Object.assign(new Error('An audio or video upload is required.'), { status: 415 });
    }
    const id = crypto.randomUUID();
    const metadata = { id, contentType, size: normalizedSize, offset: 0, owner, createdAt: Date.now(), updatedAt: Date.now() };
    fs.writeFileSync(this.metadataPath(id), JSON.stringify(metadata), { flag: 'wx' });
    fs.writeFileSync(this.mediaPath(id), Buffer.alloc(0), { flag: 'wx' });
    return metadata;
  }

  read(id, owner) {
    if (!/^[0-9a-f-]{36}$/i.test(id)) throw Object.assign(new Error('Upload not found.'), { status: 404 });
    let metadata;
    try { metadata = JSON.parse(fs.readFileSync(this.metadataPath(id), 'utf8')); }
    catch { throw Object.assign(new Error('Upload not found.'), { status: 404 }); }
    if (metadata.owner !== owner) throw Object.assign(new Error('Upload not found.'), { status: 404 });
    if (Date.now() - metadata.updatedAt > this.ttlMs) { this.remove(id); throw Object.assign(new Error('Upload expired.'), { status: 410 }); }
    return metadata;
  }

  async append(id, owner, expectedOffset, input) {
    const metadata = this.read(id, owner);
    if (Number(expectedOffset) !== metadata.offset) throw Object.assign(new Error('Upload offset does not match.'), { status: 409, offset: metadata.offset });
    let received = 0;
    const remaining = metadata.size - metadata.offset;
    const limiter = new Transform({ transform(chunk, _encoding, callback) {
      received += chunk.length;
      if (received > remaining) return callback(Object.assign(new Error('Chunk exceeds declared upload size.'), { status: 413 }));
      callback(null, chunk);
    } });
    await new Promise((resolve, reject) => {
      const output = fs.createWriteStream(this.mediaPath(id), { flags: 'a' });
      input.on('error', reject); limiter.on('error', reject); output.on('error', reject); output.on('finish', resolve);
      input.pipe(limiter).pipe(output);
    });
    metadata.offset += received; metadata.updatedAt = Date.now();
    fs.writeFileSync(this.metadataPath(id), JSON.stringify(metadata));
    return metadata;
  }

  complete(id, owner) {
    const metadata = this.read(id, owner);
    if (metadata.offset !== metadata.size) throw Object.assign(new Error('Upload is incomplete.'), { status: 409, offset: metadata.offset });
    return { metadata, stream: fs.createReadStream(this.mediaPath(id)) };
  }

  remove(id) {
    for (const target of [this.metadataPath(id), this.mediaPath(id)]) { try { fs.unlinkSync(target); } catch {} }
  }

  cleanup() {
    const now = Date.now();
    for (const name of fs.readdirSync(this.directory).filter((value) => value.endsWith('.json'))) {
      try { const metadata = JSON.parse(fs.readFileSync(path.join(this.directory, name), 'utf8')); if (now - metadata.updatedAt > this.ttlMs) this.remove(metadata.id); } catch {}
    }
  }
}

module.exports = { DEFAULT_TTL_MS, TranscriptionUploads };
