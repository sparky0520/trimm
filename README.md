# ctrlB Android

Native Android-first talking-head editor foundation. The Android module is separate from the existing Electron application and uses the same conversational, typed-operation approach.

## Build

Install Android Studio with JDK 17 and Android SDK 37, open this `android` directory, and run the `app` configuration. This workspace has a JDK 17 installation but its active shell points to Java 8, and it has no Android SDK or Gradle installation, so Gradle verification must be run from Android Studio or appropriately configured CI.

The module pins AGP 9.2, Compose BOM 2026.06, Media3 1.10, and WorkManager 2.11.2. Generate a Gradle wrapper from Android Studio before command-line CI if the repository does not yet contain one.

## Required runtime configuration

Copy `gradle.properties.example` values into the user-level Gradle properties file (`~/.gradle/gradle.properties`) or inject them in CI. Never commit real tokens.

### AssemblyAI transcription

Set `CTRLB_TRANSCRIPTION_URL` to the authenticated backend route `/v1/mobile/transcribe`. Android streams each selected media source to this endpoint using the same development bearer token as the planner. The backend owns `ASSEMBLYAI_API_KEY`, uploads the stream to AssemblyAI, and returns normalized word timestamps; the provider key is never embedded in the APK.

Analysis persists the returned words and caption cues, detects high-confidence fumbles, and then calls the Kimi planner with the existing bounded transcript-only payload. The Android worker no longer invokes or packages the native Whisper build.

### Kimi planner service

Run the planner service outside the APK:

```shell
MOONSHOT_API_KEY=... \
CTRLB_PLANNER_CLIENT_TOKEN=... \
MOONSHOT_MODEL=kimi-k3 \
npm run planner:serve
```

Deploy it behind HTTPS and point `CTRLB_PLANNER_URL` to `/v1/mobile/plan`. The static Android token is a development bootstrap mechanism only; production must issue short-lived, user-scoped JWTs through the product's authentication service. The Moonshot key always remains server-side.

### Licensed audio catalog

Host a version-1 catalog document and its media files over HTTPS. Each catalog item uses this shape:

```json
{
  "id": "calm-ambient-01",
  "kind": "MUSIC",
  "title": "Calm Ambient",
  "artist": "Example Artist",
  "assetPath": "music/calm-ambient-01.mp3",
  "downloadUrl": "https://cdn.example.com/audio/calm-ambient-01.mp3",
  "previewUrl": "https://cdn.example.com/audio/calm-ambient-01.mp3",
  "sourceUrl": "https://www.youtube.com/watch?v=...",
  "durationMs": 123000,
  "bpm": 92,
  "energy": 0.35,
  "moods": ["natural", "warm"],
  "licenseId": "youtube-audio-library:track-id",
  "checksum": "64-character-sha256",
  "attributionRequired": false,
  "attribution": null
}
```

Authorized YouTube Audio Library tracks can be ingested on a trusted development/admin machine with `yt-dlp` and `ffprobe` installed:

```shell
npm run audio:ingest -- --id calm-ambient-01 --url "https://www.youtube.com/watch?v=..." --license "youtube-audio-library:track-id" --rights-confirmed --public-base-url "https://cdn.example.com/audio" --moods natural,warm --energy 0.35
```

Add `--attribution-required --attribution "Artist — Track (license)"` when required. The command refuses unconfirmed rights, non-HTTPS and non-YouTube sources, generates an MP3 and SHA-256 checksum, and atomically updates `public/audio/catalog.v1.json`. Run ingestion outside the APK; never ship browser cookies or `yt-dlp` in Android.

Use `SOUND_EFFECT` for effects. The app rejects blank licenses, non-HTTPS downloads, and checksum mismatches. Assets are downloaded before rendering and retained in private app storage.

The catalog is intentionally optional during initial testing. New projects default to no background music and do not add a sound effect, so preview and export work while `CTRLB_AUDIO_CATALOG_URL` is empty. Music and sound-effect selection remain unavailable until a licensed catalog and CDN are configured; this follow-up is tracked as AI-042.

### Known transcription limit

The current Vercel transcription route accepts direct media uploads, but Vercel's request-body limit is much lower than the app's 1 GiB local validation limit. Small test clips can use the route; typical or large phone recordings need presigned object storage or the streaming Node service before this flow is production-ready. This follow-up is tracked as AI-041.

## Current rendering behavior

- Analysis output is persisted atomically and reloads into the editor.
- Captions are segmented from Whisper word timestamps and burned into previews/exports.
- Preview is a rendered 720p MP4, not playback of the original source.
- Export applies cuts, basic color, local speech gate/compression/leveling, background music, and positioned sound effects.
- Missing analysis, planner, audio, preview, and export configuration is reported in the editor instead of silently ignored.

Transitions and motion presets remain represented in the operation schema but are not yet rendered by Media3.

## Binary assets required for production

- An AssemblyAI account and server-side `ASSEMBLYAI_API_KEY`.
- Redistributable music and sound-effect files plus license records. No unlicensed or placeholder audio is included in this repository.
- A real authenticated planner base URL supplied through release configuration. Never embed provider API keys in the APK.

These are release inputs, not source-code substitutes. The project model, conservative detector, privacy boundary, capability fallback, persistence, workers, and Compose workflow are implemented and independently testable without them.
