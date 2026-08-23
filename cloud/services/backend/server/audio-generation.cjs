'use strict';

const fs = require('node:fs');
const path = require('node:path');

class AudioGeneration {
  constructor() {
    this.replicateApiToken = process.env.REPLICATE_API_TOKEN;
    this.elevenLabsApiToken = process.env.ELEVENLABS_API_TOKEN;
    this.s3Bucket = process.env.SFX_S3_BUCKET || 'trimm-sfx-cache';
    
    // Mock local cache to simulate S3
    this.mockS3Cache = new Map();
  }

  async augmentPrompt(userPrompt, type) {
    const apiKey = process.env.MOONSHOT_API_KEY;
    const baseURL = process.env.MOONSHOT_BASE_URL || 'https://api.moonshot.ai/v1';
    const model = process.env.MOONSHOT_MODEL || 'kimi-k3';
    
    if (!apiKey) {
      console.log(`[AudioGeneration] No Moonshot API key found, skipping prompt augmentation.`);
      return userPrompt;
    }

    const systemPrompt = type === 'bgm' 
      ? 'You are an expert prompt engineer for AI music generation. Take the user\'s short idea and expand it into a detailed, comma-separated list of musical attributes (genre, instruments, tempo, mood). Keep it under 50 words. ONLY return the final prompt.'
      : 'You are an expert prompt engineer for AI sound effect generation. Take the user\'s short idea and expand it into a descriptive prompt focusing on texture, impact, and environment. Keep it under 30 words. ONLY return the final prompt.';

    try {
      const response = await fetch(`${baseURL}/chat/completions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${apiKey}`
        },
        body: JSON.stringify({
          model: model,
          messages: [
            { role: 'system', content: systemPrompt },
            { role: 'user', content: userPrompt }
          ],
          temperature: 0.7
        })
      });
      
      const data = await response.json();
      if (data.choices && data.choices.length > 0) {
        const augmented = data.choices[0].message.content.trim();
        console.log(`[AudioGeneration] Augmented ${type} prompt: "${userPrompt}" -> "${augmented}"`);
        return augmented;
      }
    } catch (error) {
      console.error(`[AudioGeneration] Prompt augmentation failed:`, error.message);
    }
    
    return userPrompt;
  }

  async generateBgm(prompt, durationSecs) {
    const augmentedPrompt = await this.augmentPrompt(prompt, 'bgm');
    console.log(`[AudioGeneration] Generating BGM for prompt: "${augmentedPrompt}", duration: ${durationSecs}s`);
    // MOCK IMPLEMENTATION of Replicate MusicGen call
    await new Promise(resolve => setTimeout(resolve, 1000));
    
    return {
      url: `https://mock-replicate-audio.com/bgm_${Date.now()}.mp3`,
      prompt: augmentedPrompt,
      duration: durationSecs
    };
  }

  async fetchSfx(prompt) {
    const augmentedPrompt = await this.augmentPrompt(prompt, 'sfx');
    console.log(`[AudioGeneration] Fetching SFX for prompt: "${augmentedPrompt}"`);
    
    const supabaseUrl = process.env.SUPABASE_URL;
    const supabaseKey = process.env.SUPABASE_SERVICE_ROLE_KEY;
    const cacheKey = `sfx/${augmentedPrompt.toLowerCase().replace(/[^a-z0-9]/g, '_')}.mp3`;
    const publicUrl = `${supabaseUrl}/storage/v1/object/public/${this.s3Bucket}/${cacheKey}`;

    if (supabaseUrl) {
      // Check Supabase Storage Cache
      try {
        const headResponse = await fetch(publicUrl, { method: 'HEAD' });
        if (headResponse.ok) {
          console.log(`[AudioGeneration] SFX Cache HIT for: ${augmentedPrompt}`);
          return { url: publicUrl, source: 'supabase_s3_cache' };
        }
      } catch (err) {
        console.error(`[AudioGeneration] Supabase cache check failed:`, err.message);
      }
    } else {
       if (this.mockS3Cache.has(cacheKey)) {
         return { url: this.mockS3Cache.get(cacheKey), source: 'mock_s3_cache' };
       }
    }

    console.log(`[AudioGeneration] SFX Cache MISS for: ${augmentedPrompt}. Generating via AI...`);
    // MOCK IMPLEMENTATION of ElevenLabs SFX call
    await new Promise(resolve => setTimeout(resolve, 800));
    
    // Simulating generated audio buffer (in reality this is from ElevenLabs)
    const newSfxUrl = `https://mock-elevenlabs-audio.com/sfx_${Date.now()}.mp3`;
    
    // Save to Supabase Storage
    if (supabaseUrl && supabaseKey) {
      try {
        const uploadUrl = `${supabaseUrl}/storage/v1/object/${this.s3Bucket}/${cacheKey}`;
        // In reality, we'd upload the actual buffer. Here we just upload a dummy string.
        await fetch(uploadUrl, {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${supabaseKey}`,
            'Content-Type': 'audio/mpeg'
          },
          body: 'dummy audio content'
        });
        return { url: publicUrl, source: 'ai_generation' };
      } catch (err) {
        console.error(`[AudioGeneration] Failed to upload to Supabase:`, err.message);
      }
    } else {
      this.mockS3Cache.set(cacheKey, newSfxUrl);
    }
    
    return {
      url: newSfxUrl,
      source: 'ai_generation'
    };
  }
}

module.exports = {
  AudioGeneration
};
