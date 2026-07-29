# Android perception

Android performs metadata inspection, sparse frame sampling, simple pixel
statistics, PCM waveform analysis, and transcript-derived structure. Before
cloud-assisted planning, those measurements are assembled into a persisted,
request-shaped Layer 1 index:

- timestamp requests select a narrow four-second neighborhood and only the
  derivative kinds relevant to the request;
- specialist requests select the required signal kinds without assigning
  editorial meaning;
- broad requests interleave whole-source representative coverage,
  confidence-scored measured candidates, and explicit uncertainty ranges;
- weak observations expand to a denser follow-up neighborhood instead of
  being presented as certain.

The index contains timestamps, signal kinds, observation IDs, source identity,
confidence-derived selection reasons, and local analyzer provenance through
the referenced evidence graph. Layer 2 receives only later-authorized selected
ranges, never an automatic expansion of upload scope.

Current frame statistics are not semantic vision:

- average brightness approximates exposure;
- neighboring-pixel difference approximates sharpness;
- center-versus-frame contrast is labeled weak salience/crop evidence;
- differences between sampled frames approximate motion, shake, and shot changes.

Face detection, OCR, screen understanding, product/action recognition, and music classification are not implemented in production.

## Planned semantic analyzers

- Face/person: ML Kit or MediaPipe when relevant to range selection or a requested edit.
- OCR: ML Kit Text Recognition when screen or text evidence is relevant.
- Screen/UI: local text, layout, and motion candidates used to select exact ranges for Layer 2.
- Music/audio: local speech, noise, music, and transient measurements used for indexing and range selection.
- General meaning: Gemini 3.6 Flash watches only request-relevant authorized ranges by default and may request more iteratively.

Capability negotiation must inspect API level, available hardware acceleration, RAM, thermal state, storage, codec support, and model availability. Unsupported local analysis produces an honest partial or unavailable index entry; it does not silently expand cloud upload scope.
