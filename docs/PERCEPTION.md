# Android perception

Android currently performs metadata inspection, sparse frame sampling, simple pixel statistics, PCM waveform analysis, and transcript-derived structure.

Current frame statistics are not semantic vision:

- average brightness approximates exposure;
- neighboring-pixel difference approximates sharpness;
- center-versus-frame contrast is labeled weak salience/crop evidence;
- differences between sampled frames approximate motion, shake, and shot changes.

Face detection, OCR, screen understanding, product/action recognition, and music classification are not implemented in production.

## Planned implementation

- Face/person: ML Kit or MediaPipe when supported, with a cloud fallback.
- OCR: ML Kit Text Recognition for quick local results; cloud OCR for selected high-resolution material.
- Screen/UI: local text/rectangle candidates plus cloud multimodal understanding of screen state and actions.
- Music/audio: a small TFLite classifier locally where practical and a cloud audio model for richer meaning.
- General meaning: the cloud video agent watches the authorized original or proxy directly.

Capability negotiation must inspect API level, available hardware acceleration, RAM, thermal state, storage, codec support, and model availability. Unsupported local analysis falls back to cloud or produces an honest unavailable state.
