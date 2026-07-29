# Android perception

Android currently performs metadata inspection, sparse frame sampling, simple pixel statistics, PCM waveform analysis, and transcript-derived structure.

Current frame statistics are not semantic vision:

- average brightness approximates exposure;
- neighboring-pixel difference approximates sharpness;
- center-versus-frame contrast is labeled weak salience/crop evidence;
- differences between sampled frames approximate motion, shake, and shot changes.

Face detection, OCR, screen understanding, product/action recognition, and music classification are not implemented in production.

## Planned implementation

- Build the first whole-source, timestamped Layer 1 index locally. Its depth follows the request.
- Face/person: ML Kit or MediaPipe when relevant to range selection or a requested edit.
- OCR: ML Kit Text Recognition when screen or text evidence is relevant.
- Screen/UI: local text, layout, and motion candidates used to select exact ranges for Layer 2.
- Music/audio: local speech, noise, music, and transient measurements used for indexing and range selection.
- General meaning: Gemini 3.6 Flash watches only request-relevant authorized ranges by default and may request more iteratively.

Capability negotiation must inspect API level, available hardware acceleration, RAM, thermal state, storage, codec support, and model availability. Unsupported local analysis produces an honest partial or unavailable index entry; it does not silently expand cloud upload scope.
