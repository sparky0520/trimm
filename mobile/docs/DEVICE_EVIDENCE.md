# Physical-device evidence

Physical-device results are recorded against an exact app revision and a
user-authorized camera fixture. A row is complete only when perception,
interactive preview, export, thermal/memory behavior, and timing/pixel parity
all have a result. Failures remain in the matrix rather than being replaced by
emulator data.

## 2026-07-29 — Xiaomi 22041216I

- App revision: `fde4a4c`
- OS/ABI: Android 14 (API 34), `arm64-v8a`
- Memory: 7,664,728 kB total
- Fixture: newest user-authorized DCIM camera video; 56.583 s, H.264/AAC,
  1920×1080 at 30 fps with 90° display rotation, 144,463,783 bytes
- Scope: one physical standard-resolution device; other capability tiers are
  still unmeasured

| Check | Result |
| --- | --- |
| Local evidence extraction | Partial: 19 frames, 112 visual observations, 13 sound observations and one shot were persisted. |
| Speech/perception accuracy | Blocked: transcription was not configured and analysis ended with `Failed requirement` before the media index/proposal was persisted. No accuracy claim is made. |
| Destructive false positives | No destructive removal was applied, but this is not counted as a pass because analysis did not complete. |
| Time to interactive preview | Analysis worker ran for 34.853 s before failure; the canonical player displayed the source first frame and correct portrait framing. A successful proposal-to-preview time is still required. |
| Memory | Sampled app PSS was approximately 190–276 MB during analysis/export. |
| Thermal | Battery sensor moved from 38.2°C before analysis to 37.6°C afterward and peaked at 37.1°C during the measured export; no thermal rise or disconnect occurred in the valid run. |
| Export | Pass: final H.264/AAC export completed in 16.714 s and produced 142,800,811 bytes. |
| Orientation/timing parity | Pass: source and export both retain 1920×1080 coded dimensions and 90° display rotation. Export duration is 56.607 s, +24.019 ms from source (less than one 30 fps frame). |
| Pixel parity | Partial/pass at current tolerance: whole-clip downscaled SSIM was 0.9090 after device H.264 re-encoding. A cross-device acceptance threshold is not yet established. |

The fixture itself is not committed. Temporary host copies are kept outside
the repository and the original device media is never modified.

## 2026-07-30 - Xiaomi 22041216I with 4K input

- App revision: `1fcf7b3`
- OS/ABI: Android 14 (API 34), `arm64-v8a`
- Capability tier: minimum supported (provisional), reduced-resolution export
- Fixture: newest user-authorized DCIM camera video; 23.563 s, H.264/AAC,
  3840x2160 at 60 fps with -90-degree display rotation, 370,422,008 bytes
- Free storage before the run: approximately 181 GB

| Check | Result |
| --- | --- |
| 4K input and preview | Pass: the 4K60 source imported with correct portrait presentation. Local evidence persisted and preview completed without a decoder failure. |
| Speech/perception | Partial: the saved Clerk token expired during the run, so transcription ended with an authenticated HTTP 401 and no speech words were persisted. The evidence graph and safe agent clarification state still persisted. |
| Destructive false positives | Pass for this fixture: no destructive operation was applied. |
| Time to interactive preview | Partial/pass: analysis completed in 282.4 s and preview in 298.0 s from submission. Approximately four minutes were attributable to the failed transcription request, so this is not a representative local-processing latency. |
| Memory | Pass for the minimum tier: sampled app PSS remained approximately 194-289 MB. |
| Thermal | Partial: the already-warm device rose from 38.3 C before import to 40.8 C after export, peaking at 40.8 C. No thermal shutdown or disconnect occurred. |
| Export | Pass with required downgrade: export completed in 15.25 s as H.264/AAC 1280x720 at 60 fps, producing 22,940,724 bytes. The device did not qualify for 4K output. |
| Orientation/timing parity | Pass: -90-degree display rotation and 60 fps were preserved. Export duration was 23.583 s, approximately +19.3 ms from source. |
| Pixel parity | Partial/pass at provisional tolerance: whole-clip SSIM was 0.8776 after proportional 4K-to-720p downscale and device H.264 encoding. |

### Follow-up: genuine 4K hardware encode

The original downgrade was a capability-policy false negative. Android reported
both AVC and HEVC hardware encoders as 4K30-capable while rejecting the exact
1920x1080 probe. A debug-only experiment first confirmed a no-op 4K remux, then
applied an imperceptible color transform to require a genuine decode/encode pass.

| Check | Result |
| --- | --- |
| Hardware path | Pass: Media3 used `c2.mtk.avc.encoder` and produced 1,414 encoded video frames. |
| Export | Pass: genuine H.264 3840x2160 at 60 fps completed in approximately 25 s (28.8 s including MediaStore publication), producing 177,578,431 bytes at approximately 60 Mbps. |
| Memory and thermal | Pass for this fixture: sampled app PSS peaked at approximately 297 MB. Battery temperature moved from 44.8 C to 45.6 C without reported throttling or shutdown. |
| Orientation/timing parity | Pass: -90-degree display rotation and 60 fps were preserved. Output duration was 23.583 s, approximately +19.3 ms from source. |
| Pixel parity | Partial/pass at provisional native-4K tolerance: whole-clip SSIM was 0.8835. |

The capability probe now aligns requested dimensions to each encoder's published
block alignment, associates 4K claims with a hardware encoder, treats proven 4K
support as monotonic evidence for lower resolutions, and recognizes Android's
reported usable memory on nominal 8 GB-class devices.

### Follow-up: authenticated 4K analysis

After interactive Clerk sign-in, the same 4K fixture completed analysis in
62.2 s without the prior HTTP 401. It persisted seven timed words, three
reviewable speech hints, a story graph and safe agent activity. The provider
returned a malformed edit program and its bounded repair remained invalid, so
the app preserved project revision 1, applied no unsafe edit and displayed the
retry/clarify recovery state. The cloud provider now receives the canonical
decision JSON Schema for both primary generation and bounded repair; semantic
validation remains deterministic.

## 2026-07-30 - OnePlus CPH2487 with 4K input

- App revision: `1fcf7b3`
- OS/ABI: Android 14 (API 34), `arm64-v8a`
- Fixture: the same 23.563 s 4K60 camera video used above
- Blocking capability measurement: only approximately 743 MB storage was free,
  below the 1 GB minimum and 8 GB high-resolution threshold

| Check | Result |
| --- | --- |
| Analysis and preview | Pass: analysis completed in 61.2 s and preview in 78.0 s from submission. One evidence graph, seven timed words, four captions and three reviewable speech hints persisted. |
| Destructive false positives | Pass: malformed proposal output was rejected into safe retry/clarify recovery and no destructive operation was applied. |
| Memory and thermal | Sampled app PSS was approximately 138-190 MB. Battery temperature moved from 35.6 C to 36.4 C without shutdown or disconnect. |
| Export | Pass with storage-driven downgrade: 15.7 s to H.264/AAC 1280x720 at 60 fps, 29,094,574 bytes. The device cannot be counted as high-resolution capable until it has at least 8 GB free and is rerun. |
| Orientation/timing parity | Pass: -90-degree display rotation and 60 fps were preserved; duration drift was approximately +19.3 ms. |
| Pixel parity | Partial/pass at provisional tolerance: whole-clip downscaled SSIM was 0.8758. |

## 2026-07-30 - Xiaomi 22041216I rerun

- App revision: `1fcf7b3`
- OS/ABI: Android 14 (API 34), `arm64-v8a`
- Capability tier: minimum supported (provisional), reduced local perception
- Fixture: the same newest user-authorized DCIM camera video; 56.583 s,
  H.264/AAC, 1920x1080 at 30 fps with 90-degree display rotation,
  144,463,783 bytes
- Prompt: `Make this video publishable`

| Check | Result |
| --- | --- |
| Local evidence extraction | Pass: analysis persisted 322 media-index segments, 157 timed words, 25 captions, five reviewable speech hints, one evidence graph and 24 selections. |
| Speech/perception accuracy | Partial: the single-speaker transcript was coherent and identified low-confidence proper nouns and a repeated-word region for review. No hand-labelled word-error-rate or multi-speaker claim is made from this fixture. |
| Destructive false positives | Pass for this fixture: no destructive remove operation was applied automatically; the five uncertain/pause candidates remained reviewable hints. |
| Time to interactive preview | Pass: analysis completed in 37.2 s and the current-edit preview completed in 55.0 s from submission. |
| Memory | Pass for the provisional minimum tier: sampled app PSS was approximately 285-335 MB; it was 331,758 kB immediately after export. |
| Thermal | Partial: battery temperature was approximately 39.5 C after export and peaked near 40.5 C across repeated development reruns. USB charging and the accumulated session prevent attributing a clean per-run thermal delta. No thermal shutdown or disconnect occurred. |
| Export | Pass: final H.264/AAC export completed in 26.5 s and produced 63,284,271 bytes at 1920x1088 coded dimensions and 30 fps. |
| Orientation/timing parity | Pass: portrait presentation remained correct in preview. Export duration was 56.607 s, approximately +24 ms from source (less than one 30 fps frame). |
| Pixel parity | Not re-scored: the prior whole-clip SSIM of 0.9090 remains the only measurement for this device/fixture. Cross-tier acceptance tolerance is still provisional. |
| Cloud proposal | Pass with safe recovery: after the production migrations and Gemini runtime secret were installed, the authenticated run persisted and completed model inference. Its malformed proposal was rejected without applying destructive edits, persisted as `waiting_for_user` with retry/clarify options, and the safe baseline preview completed. |

## Remaining matrix

- Record an advanced local-perception device with measured Layer 1 latency.
- Establish pixel/timing tolerances from results across those tiers before
  making the provisional capability thresholds final.
