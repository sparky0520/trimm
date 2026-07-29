# Video-first agent architecture

## System shape

```text
Android / Desktop                         ctrlB cloud
-----------------                         -----------
Import media
Read metadata and rotation
Build local media index
Select request-relevant ranges --------> Media asset service
                                            |
Chat request ----------------------------> Agent run
                                            |
                                     Multimodal model watches selected ranges
                                            |
                                     Iterative specialist tools
                                     - seek/watch ranges
                                     - transcription/diarization
                                     - face/person tracking
                                     - OCR and screen/UI analysis
                                     - scene/action/product understanding
                                     - audio/music classification
                                     - compare takes
                                     - simulate candidate edit
                                            |
Activity events <------------------------ Persisted run event stream
Questions <------------------------------ Clarification state
Typed proposal <------------------------- Canonical contract service
        |
Validate and apply locally
Preview locally
Export locally
```

The initial multimodal implementation uses Gemini 3.6 Flash for the entire
agent loop. Do not add a second editorial reasoning model until versioned
real-video evaluation demonstrates a specific failure that the additional
model fixes. Kimi K3 remains only in the current legacy text/JSON planner path;
it is not the target direct-video agent.

## Media modes

The user can change the project privacy mode:

1. **Selective analysis (default):** keep source media local and upload only request-relevant or agent-requested ranges, frames, crops, or audio.
2. **Optimized proxy:** upload a visually faithful, lower-bitrate proxy with preserved timestamps and audio when broad context is required and the user permits it.
3. **Full video intelligence:** upload the original source only when the request genuinely requires whole-source inspection and the user explicitly permits it.
4. **Local only:** disable cloud-dependent understanding.

Layer 2 activation follows the request. A broad request such as “make this publishable” first receives a Layer 1 index covering the whole source, then bounded representative, candidate, and uncertainty ranges. Gemini can request more ranges iteratively when the initial selection is insufficient. The system discloses what is selected and why.

Every uploaded asset records owner, project, source fingerprint, timeline mapping, media class, consent mode, retention, deletion state, and provider disclosures.

## Agent run

An agent run is persisted and resumable. It has no hard-coded evidence-pass count.

The loop is:

1. Inspect the project, user request, media inventory, and prior decisions.
2. Watch or seek the relevant video.
3. Form explicit unknowns such as “which take is complete?” or “does this screen show the claimed setting?”
4. Call the smallest useful specialist tool.
5. Store timestamped observations and tool results.
6. Evaluate whether the edit is sufficiently grounded.
7. Continue, ask the user, propose an edit, or stop under policy.

Limits are resource policies rather than arbitrary reasoning passes: maximum elapsed time, provider cost, concurrent tool calls, uploaded bytes, and user cancellation. A limit produces an honest partial result or question.

## Safe activity stream

The UI may show:

- uploading proxy;
- transcribing audio;
- watching 01:10–01:42;
- comparing three takes;
- reading text in a product screen;
- checking a possible jump cut;
- validating the proposed timeline;
- rendering preview.

It must not expose private chain-of-thought, system prompts, credentials, or raw hidden reasoning.

## Canonical proposals

Direct video access does not remove the need for a proposal contract. A model must not send executable code, file paths, or arbitrary Media3/FFmpeg commands to a device.

The cloud owns one versioned edit-program schema. Kotlin and TypeScript models are generated from it. Clients announce supported versions during session setup. The server emits only a mutually supported version.

Both sides validate for different reasons:

- Cloud validation prevents malformed or unsupported model output from leaving the service.
- Client validation protects local media, confirms the current project revision, and prevents stale or unsafe mutation.

This turns schema mismatch into a deployment compatibility check rather than a runtime surprise.

## Rendering boundary

The agent decides what the edit should be. Device rendering decides how an approved typed operation becomes pixels and sound.

Preview and export consume the same canonical timeline. Android uses Media3; desktop uses Remotion. Renderer-specific implementation details stay local, while operation semantics and conformance fixtures are shared.

## Perception placement

Run lightweight or latency-sensitive analysis locally when hardware supports it. Run heavyweight multimodal understanding in the cloud.

| Capability | Local option | Cloud option |
| --- | --- | --- |
| Face/person | ML Kit face detection or MediaPipe | multimodal vision/person tracking service |
| OCR | ML Kit Text Recognition | high-resolution OCR on selected frames/ranges |
| Screen/UI | local object/text heuristics | multimodal screen-state and action understanding |
| Speech | on-device speech where available | production transcription and diarization |
| Music/audio | TFLite/YAMNet-style classifier | audio understanding provider |
| General video meaning | limited | primary multimodal video agent |

Local results are accelerators and fallbacks. They are not a substitute for the agent watching the video.
