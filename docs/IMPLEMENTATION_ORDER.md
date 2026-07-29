# Canonical implementation order

This is the cross-repository ticket queue for ctrlB. Use it as the default
starting point in a new coding session. Ticket numbers are meaningful only
inside their named repository.

Do not finish every cloud ticket before touching a client. Complete the smallest
acceptance slice that unlocks the next row, update the issue, and keep the
affected repositories green.

## Ordered product path

| Order | Repository | Ticket | Finish this slice before moving on |
| ---: | --- | --- | --- |
| 1 | `ctrlb-cloud` | [#4 — generated canonical contracts](https://github.com/sparky0520/ctrlb-cloud/issues/4) | ✅ Done — generated and published the agent-run, activity, evidence-request and edit-program models with shared compatibility fixtures for Kotlin and TypeScript. |
| 2 | `ctrlb-cloud` | [#1 — persistent multimodal agent loop](https://github.com/sparky0520/ctrlb-cloud/issues/1) | ✅ Done — start and continue the same durable Gemini 3.6 Flash run, persist safe activity and bounded evidence requests, and return clarification, partial result or a validated proposal. |
| 3 | `ctrlb-cloud` | [#2 — selective derivative ingestion](https://github.com/sparky0520/ctrlb-cloud/issues/2) | ✅ Done — authorize, receive, inspect, retain and delete governed request-selected derivatives; original/proxy upload remains a rejected, separate explicit mode. |
| 4 | `ctrlb-desktop` | [#5 — connect the desktop media index to the agent](https://github.com/sparky0520/ctrlb-desktop/issues/5) | Complete one real “Make this video publishable” journey: local index → selected derivatives → same cloud run → typed proposal → preview/apply. |
| 5 | `ctrlb-desktop` | [#2 — show agent work in chat](https://github.com/sparky0520/ctrlb-desktop/issues/2) | Render safe run activity, evidence requests, recoverable errors and proposal state as chat messages. Do not expose hidden chain-of-thought. |
| 6 | `ctrlb-desktop` | [#1 — adopt generated contracts](https://github.com/sparky0520/ctrlb-desktop/issues/1) | Remove remaining handwritten wire models and prove client/cloud compatibility in CI. |
| 7 | `ctrlb-desktop` | [#6 — real-media preview/export parity](https://github.com/sparky0520/ctrlb-desktop/issues/6) | Verify approved operations produce the same visible and audible result in interactive preview and final export using representative real media. |
| 8 | `ctrlb-mobile` | [#10 — minimum requirements and capability tiers](https://github.com/sparky0520/ctrlb-mobile/issues/10) | Define and test the device tiers that determine what Layer 1 can run locally and what requires Layer 2. |
| 9 | `ctrlb-mobile` | [#1 — request-shaped Layer 1 index and perception](https://github.com/sparky0520/ctrlb-mobile/issues/1) | Build broad representative coverage, measured candidates and uncertainty ranges locally without assigning editorial meaning. |
| 10 | `ctrlb-mobile` | [#11 — scene-aware speech/noise measurements](https://github.com/sparky0520/ctrlb-mobile/issues/11) | Replace global audio-floor guesses with speech-aware, per-scene measurements suitable for agent evidence. |
| 11 | `ctrlb-mobile` | [#8 — reviewable fumble hints](https://github.com/sparky0520/ctrlb-mobile/issues/8) | Provide repeated-take, pause and possible-fumble measurements as agent hints, never automatic editorial truth. |
| 12 | `ctrlb-mobile` | [#2 — prepare selective derivatives](https://github.com/sparky0520/ctrlb-mobile/issues/2) | Extract only authorized audio, frame, crop or bounded audiovisual derivatives with exact source-time mapping and cleanup. |
| 13 | `ctrlb-mobile` | [#5 — connect Android activity to chat](https://github.com/sparky0520/ctrlb-mobile/issues/5) | Start/continue one cloud run, fulfill iterative evidence requests, preserve conversation state and surface safe activity in chat. |
| 14 | `ctrlb-mobile` | [#4 — preserve orientation and output quality](https://github.com/sparky0520/ctrlb-mobile/issues/4) | Preserve detected source orientation and prevent preview/export from silently upscaling. |
| 15 | `ctrlb-mobile` | [#3 — approved-edit preview/export parity](https://github.com/sparky0520/ctrlb-mobile/issues/3) | Apply a returned proposal to the Media3 timeline and update preview immediately; export must consume the same timeline. |
| 16 | `ctrlb-mobile` | [#7 — physical-device parity evidence](https://github.com/sparky0520/ctrlb-mobile/issues/7) | Record representative device results for perception, preview and export across the supported capability tiers. |
| 17 | `ctrlb-cloud` | [#3 — durable run, cache and activity storage](https://github.com/sparky0520/ctrlb-cloud/issues/3) | Replace process-local state before production and prove restart/resume, ownership, expiry, deletion and cache reuse. Pull this forward as soon as desktop #5 needs multi-process testing. |
| 18 | `ctrlb-cloud` | [#8 — malformed proposal recovery](https://github.com/sparky0520/ctrlb-cloud/issues/8) | Repair or regenerate invalid model output without losing the run, chat, accepted decisions or last valid project revision. |
| 19 | `ctrlb-desktop` | [#4 — preserve desktop state during repair](https://github.com/sparky0520/ctrlb-desktop/issues/4) | Show a recoverable repair/retry widget and retain the same conversation, proposal context and last valid project. |
| 20 | `ctrlb-mobile` | [#9 — preserve Android state during repair](https://github.com/sparky0520/ctrlb-mobile/issues/9) | Give Android the same recoverable repair behavior without discarding chat or accepted edits. |
| 21 | `ctrlb-cloud` | [#7 — contextual speech editing](https://github.com/sparky0520/ctrlb-cloud/issues/7) | Replace fixed filler/pause editorial decisions with contextual Gemini analysis grounded by local speech and audio measurements. |

## Independent production tracks

These do not need to block the first desktop or Android editing journey:

- `ctrlb-cloud` [#5 — production billing enforcement](https://github.com/sparky0520/ctrlb-cloud/issues/5)
- `ctrlb-cloud` [#6 — production rights-cleared audio catalog](https://github.com/sparky0520/ctrlb-cloud/issues/6)
- `ctrlb-desktop` [#3 — checkout and billing management](https://github.com/sparky0520/ctrlb-desktop/issues/3)
- `ctrlb-mobile` [#6 — Google Play purchase and restore](https://github.com/sparky0520/ctrlb-mobile/issues/6)

Run them in parallel only when they do not delay the ordered product path.

## How to start a new coding session

Use the first row whose stated slice is not complete:

> Implement the next acceptance slice of `OWNER/REPOSITORY` issue `#NUMBER`.
> Read `ctrlb-cloud/docs/IMPLEMENTATION_ORDER.md`, the target issue and linked
> repository docs first. Update the issue with what landed and keep CI green.

Do not close an umbrella issue merely because one foundation commit landed.
Close it only when all of its acceptance criteria are satisfied.
