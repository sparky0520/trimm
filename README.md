# ctrlB Android

Native Android talking-head and product-demo editor. Product direction lives in [ProductVision](../../docs/ai/ProductVision.md), verified behavior in [ProjectStatus](../../docs/ai/ProjectStatus.md), and future work in [Tickets](../../docs/ai/Tickets.md).

## Build

Use JDK 17 and Android SDK 37. Open this directory in Android Studio and run the `app` configuration, or provide the equivalent SDK environment in CI. Runtime configuration belongs in the user-level Gradle properties file or CI secrets; never commit real credentials.

## Backend configuration

Set `CTRLB_BACKEND_URL` to the HTTPS origin of the long-lived Node backend. Android derives `/v1/mobile/plan` and `/v1/transcribe` from it.

The app streams each selected media source to the transcription route. The backend owns the AssemblyAI and model-provider credentials. Normal phone videos should not use the Vercel transcription adapter because platform request-body limits apply before application code can stream the upload.

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

The static planner token is a development bootstrap only. Production requires short-lived, user-scoped authentication.

## Audio catalog

The existing mobile catalog expects rights-cleared HTTPS assets with stable IDs, license metadata, checksums, previews, and attribution. A catalog is optional; projects without catalog audio continue to preview and export.

Do not treat an arbitrary YouTube URL as a YouTube Audio Library asset. The Audio Library is an authenticated YouTube Studio catalog and no Audio Library resource is documented in the YouTube Data API. The planned YouTube integration is assisted import:

1. Open YouTube Audio Library for the signed-in user.
2. Let the user download the MP3 with YouTube's own download control.
3. Import that file with the displayed track identity, license type, and copied attribution.
4. Record a checksum and rights provenance before the asset becomes renderable.

Provider adapters may automate acquisition only through documented APIs and permitted download flows. Freesound exposes API search and an OAuth-protected original-file download suitable for a sound-effects adapter. Never ship provider cookies or private-endpoint scraping code in the APK. Do not republish provider source files through a ctrlB CDN unless the captured license explicitly permits redistribution.

See [ProductVision - Audio acquisition policy](../../docs/ai/ProductVision.md#audio-acquisition-policy) and AI-042 in [Tickets](../../docs/ai/Tickets.md).

## Current rendering behavior

- Analysis output and project operations persist locally.
- Preview is a rendered 720p MP4.
- Export applies cuts, basic color, dialogue processing, captions when present, background music, and positioned sound effects.
- Missing planner, transcription, audio, preview, and export configuration produces a visible error or warning.
- Transitions and motion presets remain represented in the operation schema but are not rendered by the current Media3 path.

## Production inputs

- A deployed authenticated streaming backend.
- Server-side transcription and planner credentials.
- A rights-audited audio catalog or user-scoped provider connections.
- Redistributable audio only where the license expressly permits source-file redistribution.
- Physical-device and CI verification across representative Android capabilities.
