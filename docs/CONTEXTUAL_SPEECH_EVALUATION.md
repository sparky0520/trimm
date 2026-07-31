# Contextual speech model evaluation

The cloud authorization boundary does not permit a speech cut merely because a
local detector found a filler-like word, a long pause, low transcript
confidence, or a poor recording-quality score. Those measurements only locate
candidates. A proposal must also carry a cited contextual decision that
preserves meaning, selects a complete delivery, uses speaker-and-scene cadence,
considers surrounding audio/video and user intent, and simulates the resulting
join.

The versioned evaluation manifest is
`services/backend/test/fixtures/contextual-speech-eval.v1.json`. It defines the
required slow, fast, non-native, emotional, comedic, instructional,
multi-speaker, and noisy/uncertain cases and their expected editorial outcomes.
Private media is not committed to this repository.

Model qualification remains pending until the governed evaluation runner has:

1. supplied one checksum-addressed real-video fixture for every manifest case;
2. run the primary model against the complete transcript and authorized video windows,
   timestamp grounding, structured output, cached context, and bounded tools;
3. stored signed per-case results containing model version, prompt-policy
   revision, fixture checksum, decision, cited timestamps, and reviewer verdict;
4. passed every safety assertion, including zero cuts authorized solely by a
   word list, transcript-confidence threshold, universal pause threshold, or
   recording-quality score.

Do not change a candidate model from `pending_real_media_evaluation` to pinned
based on unit tests or synthetic metadata.
