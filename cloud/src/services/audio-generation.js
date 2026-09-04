
import fs from 'node:fs';
import path from 'node:path';
import { createClient } from '@supabase/supabase-js';

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

    let systemPrompt = '';
    if (type === 'bgm') {
      systemPrompt = 'You are an expert prompt engineer for AI music generation. Take the user\'s short idea and expand it into a detailed, comma-separated list of musical attributes (genre, instruments, tempo, mood) for a background music bed. Keep it under 50 words. ONLY return the final prompt.';
    } else if (type === 'bgm_effect') {
      systemPrompt = 'You are an expert prompt engineer for AI music generation. The user wants a short musical stinger, crescendo, or impact effect to be layered on a timeline. Take the user\'s idea and expand it into a detailed list of musical attributes (instruments, intensity, style). Keep it under 30 words. ONLY return the final prompt.';
    } else {
      systemPrompt = 'You are an expert prompt engineer for AI sound effect generation. Take the user\'s short idea and expand it into a descriptive prompt focusing on texture, impact, and environment. Keep it under 30 words. ONLY return the final prompt.';
    }

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

  async generateBgm(prompt, durationSecs, isEffect = false) {
    let augmentedPrompt = await this.augmentPrompt(prompt, isEffect ? 'bgm_effect' : 'bgm');
    console.log(`[AudioGeneration] Generating BGM for prompt: "${augmentedPrompt}", duration: ${durationSecs}s`);
    
    if (!this.replicateApiToken) {
      throw new Error('REPLICATE_API_TOKEN is not configured');
    }

    const replicateResponse = await fetch('https://api.replicate.com/v1/predictions', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.replicateApiToken}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        version: "671ac645ce5e552cc63a54a2bbff63fcf798043055d2dac5fc9e36a837eedcfb",
        input: { prompt: augmentedPrompt, duration: Math.min(30, Math.max(1, durationSecs)) }
      })
    });

    if (!replicateResponse.ok) {
      throw new Error(`Replicate API error: ${await replicateResponse.text()}`);
    }

    let prediction = await replicateResponse.json();
    const getUrl = prediction.urls.get;

    while (prediction.status !== 'succeeded' && prediction.status !== 'failed') {
      await new Promise(resolve => setTimeout(resolve, 2000));
      const pollResponse = await fetch(getUrl, {
        headers: { 'Authorization': `Bearer ${this.replicateApiToken}` }
      });
      prediction = await pollResponse.json();
    }

    if (prediction.status === 'failed') throw new Error('Replicate generation failed');

    const audioUrl = prediction.output;
    
    const supabaseUrl = process.env.SUPABASE_URL;
    const supabaseKey = process.env.SUPABASE_PUBLISHABLE_KEY;
    
    if (supabaseUrl && supabaseKey) {
       const cacheKey = `bgm/bgm_${Date.now()}.mp3`;
       const publicUrl = `${supabaseUrl}/storage/v1/object/public/${this.s3Bucket}/${cacheKey}`;
       
        try {
         const audioRes = await fetch(audioUrl);
         const audioBuffer = await audioRes.arrayBuffer();
         const supabase = createClient(supabaseUrl, supabaseKey);
         const { error } = await supabase.storage.from(this.s3Bucket).upload(cacheKey, Buffer.from(audioBuffer), {
           contentType: 'audio/mpeg'
         });
         
         if (error) {
           throw error;
         }
         return { url: publicUrl, prompt: augmentedPrompt, duration: durationSecs };
       } catch (err) {
         console.error(`[AudioGeneration] Failed to cache BGM in Supabase:`, err.message);
       }
    }
    
    return {
      url: audioUrl,
      prompt: augmentedPrompt,
      duration: durationSecs
    };
  }

  async fetchSfx(prompt) {
    const augmentedPrompt = await this.augmentPrompt(prompt, 'sfx');
    console.log(`[AudioGeneration] Fetching SFX for prompt: "${augmentedPrompt}"`);
    
    const supabaseUrl = process.env.SUPABASE_URL;
    const supabaseKey = process.env.SUPABASE_PUBLISHABLE_KEY;
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
    
    if (!supabaseUrl || !supabaseKey) {
      console.warn('[AudioGeneration] Supabase keys missing. Will bypass upload and return Data URI.');
    }

    if (!this.elevenLabsApiToken) {
      throw new Error('ELEVENLABS_API_TOKEN is not configured');
    }

    let audioBuffer = null;
    try {
      const response = await fetch('https://api.elevenlabs.io/v1/sound-generation', {
        method: 'POST',
        headers: {
          'xi-api-key': this.elevenLabsApiToken,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          text: augmentedPrompt,
          duration_seconds: 2
        })
      });

      if (response.ok) {
        audioBuffer = await response.arrayBuffer();
      } else {
        throw new Error(`ElevenLabs error: ${await response.text()}`);
      }
    } catch (err) {
      console.error(`[AudioGeneration] ElevenLabs request failed:`, err.message);
      throw err;
    }
    
    // Save to Supabase Storage
    try {
      const supabase = createClient(supabaseUrl, supabaseKey);
      const { error } = await supabase.storage.from(this.s3Bucket).upload(cacheKey, Buffer.from(audioBuffer), {
        contentType: 'audio/mpeg'
      });
      
      if (error) {
        throw error;
      }
      
      return { url: publicUrl, source: 'ai_generation' };
    } catch (err) {
      console.warn(`[AudioGeneration] Failed to upload to Supabase, falling back to Data URI:`, err.message);
      
      // Fallback: Return the audio as a Base64 Data URI
      const base64Audio = Buffer.from(audioBuffer).toString('base64');
      const dataUri = `data:audio/mpeg;base64,${base64Audio}`;
      return { url: dataUri, source: 'ai_generation_data_uri' };
    }
  }
}

export { 
  AudioGeneration
 };
