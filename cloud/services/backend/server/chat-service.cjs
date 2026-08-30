const { createClient } = require('@supabase/supabase-js');
const transcriptionCore = require('./transcription-core.cjs');
const { createGeminiDecisionProvider } = require('./gemini-agent.cjs');

const supabase = createClient(
  process.env.SUPABASE_URL || '',
  process.env.SUPABASE_SECRET_KEY || ''
);
const agentDecisionProvider = createGeminiDecisionProvider();

class ChatService {
  async processEditJob(checkpointId, chat_id, parent_id, audioBuffer, prompt) {
    try {
      // 1. Mark as processing (already done by the caller, but good to ensure)
      await supabase.from('checkpoints')
        .update({ status: 'processing' })
        .eq('id', checkpointId);

      // 2. Transcribe Audio (if provided)
      let transcript = null;
      if (audioBuffer && audioBuffer.length > 0) {
        // We mock a request for transcriptionCore.transcribe
        // Or if transcriptionCore has a direct function, we call it.
        // For now, let's assume we can upload to assembly via the core.
        // Actually transcriptionCore takes a stream or readable in `transcribe(req)`.
        const { Readable } = require('stream');
        const stream = Readable.from(audioBuffer);
        const transcriptionResult = await transcriptionCore.transcribe(stream);
        transcript = transcriptionResult.text;
        
        // Save transcript asset
        await supabase.from('assets').insert({
          chat_id,
          checkpoint_id: checkpointId,
          type: 'transcript',
          storage_url: 'local-transcript', // Just a placeholder, AssemblyAI text
        });
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

module.exports = { ChatService };
