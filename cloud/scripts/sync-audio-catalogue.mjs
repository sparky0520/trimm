#!/usr/bin/env node

/**
 * sync-audio-catalogue.mjs
 * Uploads all 531 audio catalogue items from local disk to Supabase Storage bucket 'trimm-audio-catalogue'.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createClient } from '@supabase/supabase-js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const SUPABASE_URL = process.env.SUPABASE_URL || 'https://gahrfsmpnakrjmvhikzf.supabase.co';
const SUPABASE_SECRET_KEY = process.env.SUPABASE_SECRET_KEY || 'sb_secret_F0ZJGyWXmG41K4AbcFlHFg_VW4rLkzK';
const BUCKET_NAME = process.env.AUDIO_BUCKET_NAME || 'trimm-audio-catalogue';

const supabase = createClient(SUPABASE_URL, SUPABASE_SECRET_KEY);

const cataloguePath = path.resolve(__dirname, '../src/data/catalogue.json');
const rawAssetsDir = '/Users/darsh/audio generation/trimm_audio/audio';
const fallbackAssetsDir = path.resolve(__dirname, '../assets/audio');
const assetsDir = fs.existsSync(rawAssetsDir) ? rawAssetsDir : fallbackAssetsDir;

async function ensureBucket() {
  const { data: buckets, error: listError } = await supabase.storage.listBuckets();
  if (listError) {
    console.error('Error listing buckets:', listError.message);
    return;
  }
  const exists = buckets.some(b => b.name === BUCKET_NAME || b.id === BUCKET_NAME);
  if (!exists) {
    console.log(`Creating bucket '${BUCKET_NAME}'...`);
    const { error: createError } = await supabase.storage.createBucket(BUCKET_NAME, {
      public: true,
      fileSizeLimit: 52428800
    });
    if (createError) console.error('Error creating bucket:', createError.message);
    else console.log(`Bucket '${BUCKET_NAME}' created as public.`);
  } else {
    console.log(`Bucket '${BUCKET_NAME}' is ready.`);
  }
}

async function runUpload() {
  if (!fs.existsSync(cataloguePath)) {
    console.error(`Catalogue not found at: ${cataloguePath}`);
    process.exit(1);
  }

  const cat = JSON.parse(fs.readFileSync(cataloguePath, 'utf8'));
  const items = cat.items || [];
  console.log(`Found ${items.length} items to sync from ${assetsDir} to Supabase bucket '${BUCKET_NAME}'...`);

  await ensureBucket();

  let uploaded = 0;
  let skipped = 0;
  let failed = 0;
  const CONCURRENCY = 8;

  async function uploadOne(item, index) {
    const relPath = item.file.replace(/^audio\//, '');
    const localFilePath = path.join(assetsDir, relPath);

    if (!fs.existsSync(localFilePath)) {
      console.warn(`[${index + 1}/${items.length}] Missing file locally: ${localFilePath}`);
      failed++;
      return;
    }

    try {
      const fileBuffer = fs.readFileSync(localFilePath);
      const { error } = await supabase.storage.from(BUCKET_NAME).upload(relPath, fileBuffer, {
        contentType: 'audio/mpeg',
        upsert: true
      });

      if (error) {
        console.error(`[${index + 1}/${items.length}] Failed ${item.id} (${relPath}):`, error.message);
        failed++;
      } else {
        uploaded++;
        if (uploaded % 25 === 0 || uploaded === items.length) {
          console.log(`[Progress] Uploaded ${uploaded}/${items.length} files...`);
        }
      }
    } catch (err) {
      console.error(`[${index + 1}/${items.length}] Exception on ${item.id}:`, err.message);
      failed++;
    }
  }

  // Pool execution
  const queue = items.map((item, index) => ({ item, index }));
  const workers = Array.from({ length: CONCURRENCY }, async () => {
    while (queue.length > 0) {
      const { item, index } = queue.shift();
      await uploadOne(item, index);
    }
  });

  await Promise.all(workers);

  console.log(`\n=== SYNC COMPLETE ===`);
  console.log(`Total: ${items.length}`);
  console.log(`Uploaded / Updated: ${uploaded}`);
  console.log(`Failed: ${failed}`);
  console.log(`Public CDN Base URL: ${SUPABASE_URL}/storage/v1/object/public/${BUCKET_NAME}`);
}

runUpload().catch(err => {
  console.error('Fatal sync error:', err);
  process.exit(1);
});
