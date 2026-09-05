import { createClient } from '@supabase/supabase-js';
import dotenv from 'dotenv';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
dotenv.config({ path: path.join(__dirname, '..', '.env.local') });

const supabase = createClient(
  process.env.SUPABASE_URL || '',
  process.env.SUPABASE_SECRET_KEY || ''
);

async function run() {
  console.log("Fetching all chats...");
  const { data: chats, error } = await supabase.from('chats').select('id, project_data');
  if (error) {
    console.error("Failed to fetch chats:", error);
    process.exit(1);
  }

  let updatedCount = 0;

  for (const chat of chats) {
    if (chat.project_data && chat.project_data.analysis && chat.project_data.analysis.revisionConversation) {
      // Migrate key
      chat.project_data.analysis.chatHistory = chat.project_data.analysis.revisionConversation;
      delete chat.project_data.analysis.revisionConversation;

      const { error: updateError } = await supabase
        .from('chats')
        .update({ project_data: chat.project_data })
        .eq('id', chat.id);

      if (updateError) {
        console.error(`Failed to update chat ${chat.id}:`, updateError);
      } else {
        console.log(`Migrated chat ${chat.id}`);
        updatedCount++;
      }
    }
  }

  console.log(`Migration complete. Updated ${updatedCount} chats.`);
}

run().catch(console.error);
