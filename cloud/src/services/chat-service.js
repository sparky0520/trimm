import { Readable } from "node:stream";
import { createClient } from '@supabase/supabase-js';
import * as transcriptionCore from './transcription-core.js';
import { createOpenAIDecisionProvider } from './openai-agent.js';

import fetch from 'node-fetch'; // or rely on global fetch in Node 18+

const supabase = createClient(
  process.env.SUPABASE_URL || '',
  process.env.SUPABASE_SECRET_KEY || ''
);
const agentDecisionProvider = createOpenAIDecisionProvider();

class ChatService {
  async processEditJob(checkpointId, chat_id, parent_id, audioBuffer, prompt) {
    try {
      // 1. Mark as processing ONLY if it's currently pending to avoid race conditions
      const { data, error } = await supabase.from('checkpoints')
        .update({ status: 'processing' })
        .eq('id', checkpointId)
        .eq('status', 'pending')
        .select();

      if (error || !data || data.length === 0) {
        console.warn(`[ChatService] Checkpoint ${checkpointId} is not pending. It might be already processed.`);
        return;
      }

      // 2. Transcribe Audio (if provided)
      let transcript = null;
      if (audioBuffer && audioBuffer.length > 0) {
        // We mock a request for transcriptionCore.transcribe
        // Or if transcriptionCore has a direct function, we call it.
        // For now, let's assume we can upload to assembly via the core.
        // Actually transcriptionCore takes a stream or readable in `transcribe(req)`.
        
        const stream = Readable.from(audioBuffer);
        const transcriptionResult = await transcriptionCore.transcribe(stream);
        transcript = transcriptionResult.text;
      }

      // 3. Run LLM
      // Use existing agentDecisionProvider.generatePlan or executeTask
      const fullPrompt = transcript ? `Transcript: ${transcript}\n\nUser Request: ${prompt}` : prompt;
      const agentResult = await agentDecisionProvider.executeTask({
        prompt: fullPrompt,
        sources: [], // Provide sources if needed
        videoFiles: [], // JSON edits only
      });

      // 4. Update Checkpoint as completed with JSON edits
      await supabase.from('checkpoints')
        .update({
          status: 'completed',
          edit_instructions: agentResult
        })
        .eq('id', checkpointId);

    } catch (error) {
      console.error('[ChatService] Error processing edit job:', error);
      await supabase.from('checkpoints')
        .update({ status: 'failed' })
        .eq('id', checkpointId);
    }
  }
}

export {  ChatService  };
