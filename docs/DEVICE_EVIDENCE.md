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

## Remaining matrix

- Re-run this fixture after the analysis failure is fixed and with
  transcription configured.
- Record at least one minimum/reduced-resolution device.
- Record an advanced local-perception device with measured Layer 1 latency.
- Record a high-resolution-capable device with a 4K camera fixture.
- Establish pixel/timing tolerances from results across those tiers before
  making the provisional capability thresholds final.
