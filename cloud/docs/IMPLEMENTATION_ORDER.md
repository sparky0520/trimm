# Canonical implementation order

This is the cross-repository ticket queue for trimm. Use it as the default
starting point in a new coding session. Ticket numbers are meaningful only
inside their named repository.

Do not finish every cloud ticket before touching a client. Complete the smallest
acceptance slice that unlocks the next row, update the issue, and keep the
affected repositories green.

## Ordered product path

| Order | Repository | Ticket | Finish this slice before moving on |
| ---: | --- | --- | --- |
| 1 | `trimm-cloud` | [#4 — generated canonical contracts](https://github.com/sparky0520/trimm-cloud/issues/4) | ✅ Done — generated and published the agent-run, activity, evidence-request and edit-program models with shared compatibility fixtures for Kotlin and TypeScript. |
| 2 | `trimm-cloud` | [#1 — persistent multimodal agent loop](https://github.com/sparky0520/trimm-cloud/issues/1) | ✅ Done — start and continue the same durable Gemini 3.6 Flash run, persist safe activity and bounded evidence requests, and return clarification, partial result or a validated proposal. |
| 3 | `trimm-cloud` | [#2 — governed video ingestion](https://github.com/sparky0520/trimm-cloud/issues/2) | ✅ Done — authorize, receive, retain and delete complete short-video or agent-requested video windows. |
| 4 | `trimm-desktop` | [#5 — connect the desktop media index to the agent](https://github.com/sparky0520/trimm-desktop/issues/5) | ✅ Done — complete one “Make this video publishable” journey from local index through governed selected derivatives and the same cloud run to a validated preview/apply proposal. |
| 5 | `trimm-desktop` | [#2 — show agent work in chat](https://github.com/sparky0520/trimm-desktop/issues/2) | ✅ Done — persist and render safe run activity, evidence/upload progress, recoverable errors, cancellation and validated proposal state in chat without exposing hidden chain-of-thought. |
| 6 | `trimm-desktop` | [#1 — adopt generated contracts](https://github.com/sparky0520/trimm-desktop/issues/1) | ✅ Done — desktop consumes pinned cloud-generated TypeScript/runtime models, negotiates supported versions, and verifies shared fixture and artifact integrity in CI. |
| 7 | `trimm-desktop` | [#6 — real-media preview/export parity](https://github.com/sparky0520/trimm-desktop/issues/6) | ✅ Done — approved trims, source audio, mute policy, gain, overlays and zooms share one canonical preview/export contract verified against checksum-pinned representative real media in CI. |
| 8 | `trimm-mobile` | [#10 — minimum requirements and capability tiers](https://github.com/sparky0520/trimm-mobile/issues/10) | ✅ Done — provisional measured tiers and downgrade explanations determine local editing, Layer 2 perception, reduced-resolution export and unsupported devices without requiring an NPU; final thresholds remain gated on physical-device issue #7. |
| 9 | `trimm-mobile` | [#1 — request-shaped Layer 1 index and perception](https://github.com/sparky0520/trimm-mobile/issues/1) | ✅ Done — every analyzed request persists a local timestamped index shaped as exact, specialist or broad coverage; broad indexes interleave representative, measured-candidate and denser uncertainty ranges without assigning editorial meaning. |
| 10 | `trimm-mobile` | [#11 — scene-aware speech/noise measurements](https://github.com/sparky0520/trimm-mobile/issues/11) | ✅ Done — transcript/energy speech masks and acoustic scenes produce per-scene noise, speech, SNR, clipping and silence evidence while rejecting transient/tonal samples and reporting uncertainty when no clean noise window exists. |
| 11 | `trimm-mobile` | [#8 — reviewable fumble hints](https://github.com/sparky0520/trimm-mobile/issues/8) | ✅ Done — pauses, possible fillers, transcript uncertainty and same-speaker repeated phrases persist as contextual provenance-bearing hints; no fixed threshold or word list creates an enabled speech cut. |
| 12 | `trimm-mobile` | [#2 — prepare video windows](https://github.com/sparky0520/trimm-mobile/issues/2) | ✅ Done — Android prepares authorized audiovisual MP4 windows with exact source-time manifests, duration/byte enforcement, checksums and cancellation/expiry cleanup. |
| 13 | `trimm-mobile` | [#5 — connect Android activity to chat](https://github.com/sparky0520/trimm-mobile/issues/5) | ✅ Done — Android starts one contract-v1 run, persists ordered safe activity in the project/chat, fulfills repeated authorized derivative requests with resumable uploads, and continues the same run to a terminal proposal, clarification, partial, failure or cancellation state. |
| 14 | `trimm-mobile` | [#4 — preserve orientation and output quality](https://github.com/sparky0520/trimm-mobile/issues/4) | ✅ Done — rotation-aware displayed dimensions now drive import preview, draft, rendered preview and export; device fallback and mixed-source ceilings preserve exact framing without upscaling low-resolution, portrait, landscape, square or 4K media. |
| 15 | `trimm-mobile` | [#3 — approved-edit preview/export parity](https://github.com/sparky0520/trimm-mobile/issues/3) | ✅ Done — applying a validated proposal updates the persisted project and interactive Media3 `CompositionPlayer` immediately, while preview and `Transformer` export share one canonical composition for timing, cuts, orientation, captions, effects and audio. |
| 16 | `trimm-mobile` | [#7 — physical-device parity evidence](https://github.com/sparky0520/trimm-mobile/issues/7) | 🟡 In progress — real-camera transcription, local evidence, authenticated cloud-agent safe recovery, preview, export, memory and timing/orientation parity pass on physical hardware. Xiaomi completed a genuine hardware H.264 4K60 encode after alignment-aware probing exposed the prior policy false negative. Pixel tolerance remains provisional and an advanced-local device with measured Layer 1 latency is still required. |
| 17 | `trimm-cloud` | [#3 — durable run, cache and activity storage](https://github.com/sparky0520/trimm-cloud/issues/3) | ✅ Done — production uses private transaction-locked Postgres state and byte storage for derivative jobs, activity and usage; restart/resume, ownership, expiry and deletion are covered, with version-exact provider-context and media-index caches supporting range invalidation, retention, provenance, hit and cost accounting. |
| 18 | `trimm-cloud` | [#8 — malformed proposal recovery](https://github.com/sparky0520/trimm-cloud/issues/8) | ✅ Done — malformed output gets one bounded structural-only repair call, both redacted attempts and safe events persist, intent-changing or still-invalid repairs remain non-executable, and retry/clarification resumes the same run without changing accepted edits or project revision. |
| 19 | `trimm-desktop` | [#4 — preserve desktop state during repair](https://github.com/sparky0520/trimm-desktop/issues/4) | ✅ Done — canonical repair activity persists with the project and renders a compact Retry repair / Regenerate proposal / Review issue widget; authenticated actions continue the same run while conversation, accepted edits, evidence, project revision and last valid preview remain unchanged. |
| 20 | `trimm-mobile` | [#9 — preserve Android state during repair](https://github.com/sparky0520/trimm-mobile/issues/9) | ✅ Done — canonical repair activity and recovery choices persist in the Android project/chat; retry, regenerate and issue review keep the same run, accepted edits, evidence, revision and preview while only a final validated proposal is reviewable. |
| 21 | `trimm-cloud` | [#7 — contextual speech editing](https://github.com/sparky0520/trimm-cloud/issues/7) | 🟡 In progress — contextual speech-cut authorization, canonical decision metadata, bounded evidence policy, and the eight-scenario evaluation manifest are implemented; production qualification still requires governed fixtures and credentials. |

## Independent production tracks

These do not need to block the first desktop or Android editing journey:

- `trimm-cloud` [#5 — production billing enforcement](https://github.com/sparky0520/trimm-cloud/issues/5)
- `trimm-cloud` [#6 — production rights-cleared audio catalog](https://github.com/sparky0520/trimm-cloud/issues/6)
- `trimm-desktop` [#3 — checkout and billing management](https://github.com/sparky0520/trimm-desktop/issues/3)
- `trimm-mobile` [#6 — Google Play purchase and restore](https://github.com/sparky0520/trimm-mobile/issues/6)

Run them in parallel only when they do not delay the ordered product path.

## How to start a new coding session

Use the first row whose stated slice is not complete:

> Implement the next acceptance slice of `OWNER/REPOSITORY` issue `#NUMBER`.
> Read `trimm-cloud/docs/IMPLEMENTATION_ORDER.md`, the target issue and linked
> repository docs first. Update the issue with what landed and keep CI green.

Do not close an umbrella issue merely because one foundation commit landed.
Close it only when all of its acceptance criteria are satisfied.
