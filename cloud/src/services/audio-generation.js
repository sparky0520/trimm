// Backwards-compatibility wrapper for AudioGeneration.
// AI generation (ElevenLabs, Replicate) has been completely removed in favor of the curated 531-item CC0 catalogue.

import { AudioCatalogueService } from './audio-catalogue.js';

class AudioGeneration {
  constructor(options = {}) {
    this.catalogue = new AudioCatalogueService(options);
  }

  async generateBgm(prompt, durationSecs, isEffect = false, chatId = null) {
    return this.catalogue.generateBgm(prompt, durationSecs, isEffect, chatId);
  }

  async fetchSfx(prompt) {
    return this.catalogue.fetchSfx(prompt);
  }
}

export { AudioGeneration };
