# ctrlB Android

Native Android talking-head and product-demo editor. Product direction lives in [ProductVision](../../docs/ai/ProductVision.md), the local/cloud boundary in [architecture](../../docs/ai/architecture.md), verified behavior in [ProjectStatus](../../docs/ai/ProjectStatus.md), and all work in [tickets](../../docs/ai/tickets.md).

## Build

Use JDK 17 and Android SDK 37. Open this directory in Android Studio and run the `app` configuration, or provide the equivalent SDK environment in CI. Runtime configuration belongs in the user-level Gradle properties file or CI secrets; never commit real credentials.

## Backend configuration

Set `CTRLB_BACKEND_URL` to the HTTPS origin of the long-lived Node backend. Android derives `/v1/mobile/plan` and `/v1/transcribe` from it.

The current implementation uploads selected media sources in resumable chunks for transcription. This is legacy behavior, not the target architecture. Ticket 2 replaces it with local Layer 1 audio extraction and scoped compressed-audio jobs so the original video remains on the device. The backend continues to own AssemblyAI and model-provider credentials.

Development example:

```shell
docker build -f services/backend/Dockerfile -t ctrlb-streaming .
docker run --rm -p 8787:8787 \
  -e ASSEMBLYAI_API_KEY=... \
  -e MOONSHOT_API_KEY=... \
  -e CTRLB_PLANNER_CLIENT_TOKEN=... \
  ctrlb-streaming
```

Point Android at the deployed HTTPS origin:

```properties
CTRLB_BACKEND_URL=https://api.example.com
```

The static planner token is a development bootstrap only and works only when the backend explicitly sets `CTRLB_ALLOW_BOOTSTRAP_AUTH=true`. Production uses OIDC access tokens verified against `CTRLB_AUTH_ISSUER`, `CTRLB_AUTH_AUDIENCE`, and `CTRLB_AUTH_JWKS_URL`. The verified token subject—not a caller-provided ID—owns transcription upload sessions and is the identity boundary for future projects and subscription entitlements.

## Audio catalog

The existing mobile catalog expects rights-cleared HTTPS assets with stable IDs, license metadata, checksums, previews, and attribution. A catalog is optional; projects without catalog audio continue to preview and export.

Do not treat an arbitrary YouTube URL as a YouTube Audio Library asset. The Audio Library is an authenticated YouTube Studio catalog and no Audio Library resource is documented in the YouTube Data API. The planned YouTube integration is assisted import:

1. Open YouTube Audio Library for the signed-in user.
2. Let the user download the MP3 with YouTube's own download control.
3. Import that file with the displayed track identity, license type, and copied attribution.
4. Record a checksum and rights provenance before the asset becomes renderable.

Provider adapters may automate acquisition only through documented APIs and permitted download flows. Freesound exposes API search and an OAuth-protected original-file download suitable for a sound-effects adapter. Never ship provider cookies or private-endpoint scraping code in the APK. Do not republish provider source files through a ctrlB CDN unless the captured license explicitly permits redistribution.

The backend `audio:import` command analyzes imports with `ffprobe`/`ffmpeg`, Chromaprint's
`fpcalc`, and `aubio tempo`; these executables must be installed on the ingestion worker.
Analysis creates derived catalog metadata and a preview gain instruction. It never rewrites
the acquired source or its rights record.

See [ProductVision - Audio acquisition policy](../../docs/ai/ProductVision.md#audio-acquisition-policy) and Ticket 3 in [tickets](../../docs/ai/tickets.md).

## Current rendering behavior

- Analysis output and project operations persist locally.
- Preview is a rendered 720p MP4.
- Export applies cuts, explicitly frame-derived color when present, dialogue processing, captions when present, background music, and positioned sound effects.
- Missing planner, transcription, audio, preview, and export configuration produces a visible error or warning.
- Evidence-gated semantic callouts, spotlights, and safe reframes share the Media3 preview/export path. Legacy generic transitions and motion presets remain readable in the project schema but stay excluded from planning and UI.
- Color is not offered automatically until frame analysis supplies evidence. Operation cards identify transcript evidence, user preference, or a documented default.

## Production inputs

- A deployed authenticated streaming backend.
- Server-side transcription and planner credentials.
- A rights-audited audio catalog or user-scoped provider connections.
- Redistributable audio only where the license expressly permits source-file redistribution.
- Physical-device and CI verification across representative Android capabilities.

## Representative editorial evaluation

Run `npm run fixtures:prepare:mobile` from the repository root to prepare the
versioned real-camera, multi-clip editorial fixture. The fixture manifest,
ground truth, provenance, and evaluation contract are committed; downloaded
CC-licensed media remains ignored. Later perception and planning acceptance
must exercise this fixture (or another versioned redistributable real-media
fixture), not synthetic unit inputs alone. See
[Ticket 5 delivery notes](../../docs/ai/tickets.md#ticket-5---add-representative-mobile-editorial-fixtures-and-evaluation-p0).
