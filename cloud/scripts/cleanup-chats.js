import 'dotenv/config';
import { createClient } from '@supabase/supabase-js';

const supabaseUrl = process.env.SUPABASE_URL;
const supabaseKey = process.env.SUPABASE_SECRET_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY;

if (!supabaseUrl || !supabaseKey) {
  console.error("Missing SUPABASE_URL or SUPABASE_SECRET_KEY in environment.");
  process.exit(1);
}

const supabase = createClient(supabaseUrl, supabaseKey);
const bgmBucket = process.env.SFX_S3_BUCKET || 'trimm-sfx-cache';

async function cleanup() {
  console.log("Starting cleanup of all chats and BGM files to provide a fresh start...");

  // 1. Fetch all chats
  const { data: chats, error: fetchError } = await supabase.from('chats').select('id');
  if (fetchError) {
    console.error("Error fetching chats:", fetchError.message);
    process.exit(1);
  }

  if (!chats || chats.length === 0) {
    console.log("No chats found. You are already at a fresh start!");
    return;
  }

  console.log(`Found ${chats.length} chats to delete.`);

  // 2. Iterate through chats and delete their associated BGM files
  for (const chat of chats) {
    const chatId = chat.id;
    console.log(`Cleaning up BGM files for chat ${chatId}...`);
    
    const { data: bgmFiles, error: listError } = await supabase.storage.from(bgmBucket).list(`bgm/${chatId}`);
    
    if (listError) {
      console.warn(`  Warning: Could not list BGM files for chat ${chatId}:`, listError.message);
      continue;
    }

    if (bgmFiles && bgmFiles.length > 0) {
      const bgmPaths = bgmFiles.map(f => `bgm/${chatId}/${f.name}`);
      const { error: removeError } = await supabase.storage.from(bgmBucket).remove(bgmPaths);
      
      if (removeError) {
        console.warn(`  Warning: Failed to remove some BGM files for chat ${chatId}:`, removeError.message);
      } else {
        console.log(`  Successfully deleted ${bgmPaths.length} BGM files for chat ${chatId}.`);
      }
    } else {
      console.log(`  No BGM files found for chat ${chatId}.`);
    }
  }

  // 3. Delete all chats
  console.log("Deleting chat records from the database...");
  const chatIds = chats.map(c => c.id);
  
  // We chunk the deletion just in case there are thousands of chats
  const chunkSize = 100;
  for (let i = 0; i < chatIds.length; i += chunkSize) {
    const chunk = chatIds.slice(i, i + chunkSize);
    const { error: deleteError } = await supabase.from('chats').delete().in('id', chunk);
    
    if (deleteError) {
      console.error("Error deleting chats chunk:", deleteError.message);
    } else {
      console.log(`Deleted chunk of ${chunk.length} chats.`);
    }
  }

  console.log("Cleanup complete! You now have a fresh start.");
}

cleanup().catch(console.error);
