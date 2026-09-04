
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Transform } from 'node:stream';
import { DEFAULT_MAX_BYTES } from './transcription-core.js';
import { requireAuthentication, requireOwner } from '../middleware/authorization.js';

const DEFAULT_TTL_MS = 6 * 60 * 60 * 1000;

class TranscriptionUploads {
  constructor(options = {}) {
    this.directory = options.directory || path.join(os.tmpdir(), 'trimm-transcription-uploads');
    this.maxBytes = options.maxBytes || DEFAULT_MAX_BYTES;
    this.ttlMs = options.ttlMs || DEFAULT_TTL_MS;
    this.active = new Set();
    fs.mkdirSync(this.directory, { recursive: true });
  }

  metadataPath(id) { return path.join(this.directory, `${id}.json`); }
  mediaPath(id) { return path.join(this.directory, `${id}.media`); }

  create({ contentType, size, owner }) {
    if (typeof owner !== 'string' || owner.length === 0) throw Object.assign(new Error('Authentication required.'), { status: 401 });
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
    requireOwner({ id: owner }, metadata.owner);
    if (Date.now() - metadata.updatedAt > this.ttlMs) { this.remove(id); throw Object.assign(new Error('Upload expired.'), { status: 410 }); }
    return metadata;
  }

  readForPrincipal(id, principal) {
    requireAuthentication(principal);
    return this.read(id, principal.id);
  }

  async append(id, owner, expectedOffset, input) {
    const metadata = this.read(id, owner);
    if (Number(expectedOffset) !== metadata.offset) throw Object.assign(new Error('Upload offset does not match.'), { status: 409, offset: metadata.offset });
    if (this.active.has(id)) throw Object.assign(new Error('Upload is already being updated.'), { status: 409, offset: metadata.offset });
    this.active.add(id);
    let received = 0;
    const remaining = metadata.size - metadata.offset;
    const limiter = new Transform({ transform(chunk, _encoding, callback) {
      received += chunk.length;
      if (received > remaining) return callback(Object.assign(new Error('Chunk exceeds declared upload size.'), { status: 413 }));
      callback(null, chunk);
    } });
    try {
      await new Promise((resolve, reject) => {
        const output = fs.createWriteStream(this.mediaPath(id), { flags: 'r+', start: metadata.offset });
        let settled = false;
        const finish = (error) => {
          if (settled) return;
          settled = true;
          if (error) reject(error); else resolve();
        };
        input.on('error', finish); limiter.on('error', finish); output.on('error', finish); output.on('finish', () => finish());
        input.pipe(limiter).pipe(output);
      });
      metadata.offset += received; metadata.updatedAt = Date.now();
      fs.writeFileSync(this.metadataPath(id), JSON.stringify(metadata));
      return metadata;
    } catch (error) {
      try { fs.truncateSync(this.mediaPath(id), metadata.offset); } catch {}
      throw error;
    } finally {
      this.active.delete(id);
    }
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
    const referenced = new Set();
    for (const name of fs.readdirSync(this.directory).filter((value) => value.endsWith('.json'))) {
      const fallbackId = name.slice(0, -5);
      try {
        const metadata = JSON.parse(fs.readFileSync(path.join(this.directory, name), 'utf8'));
        referenced.add(`${metadata.id}.media`);
        if (!this.active.has(metadata.id) && now - metadata.updatedAt > this.ttlMs) this.remove(metadata.id);
      } catch {
        if (!this.active.has(fallbackId)) this.remove(fallbackId);
      }
    }
    for (const name of fs.readdirSync(this.directory).filter((value) => value.endsWith('.media'))) {
      const id = name.slice(0, -6);
      if (!referenced.has(name) && !this.active.has(id)) {
        try { fs.unlinkSync(path.join(this.directory, name)); } catch {}
      }
    }
  }
}

export {  DEFAULT_TTL_MS, TranscriptionUploads  };
