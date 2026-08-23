# Android perception and video-agent input

Android transcribes the complete audio track of every selected source through AssemblyAI before the Gemini agent starts. Transcript words retain source-relative timestamps, confidence, and speaker IDs when diarization is available.

For one source of 30 seconds or less, the cloud authorizes a complete `video/mp4` analysis upload before the first Gemini inference. Longer sources begin with the full transcript. Gemini can then call `inspect_video_range` for any source-relative window up to 30 seconds. Android uses Media3 to create that exact audiovisual MP4 and validates the job ID, source, range, duration, byte limit, format, expiry, and cancellation state before upload.

Low-resolution processing is the default. A high-resolution window is authorized only after Gemini has inspected an overlapping low-resolution window and states what remains unclear. The old cloud evidence types for audio-only ranges, frames, crops, text, and generic evidence are not supported.

Android still performs local technical analysis for editing and rendering:

- transcript-derived words, sentences, speakers, pauses, and possible disfluencies;
- PCM loudness, clipping, silence, noise-floor, SNR, and uncertainty measurements;
- sparse-frame brightness, sharpness, approximate motion, and possible shot changes.

These measurements are not semantic understanding. Local face detection, OCR, screen understanding, product/action recognition, and music classification are not implemented. Gemini receives video directly when semantic audiovisual understanding is required.

Every Gemini call reports token usage and an estimated USD cost. The chat shows the cumulative conversation total and an expandable per-call breakdown. Usage is recorded but does not terminate analysis.
