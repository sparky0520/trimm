# ctrlB

ctrlB is a conversational desktop video editor for turning one narrated developer demo or screen recording into a clean, publishable video. Describe the result in chat, review the proposed edits, then apply or reject them; you do not need to edit project JSON or work in a traditional timeline.

## Current workflow

1. Import one video.
2. ctrlB reads its technical metadata, creates a timestamped transcript when audio is present, detects silence, and extracts representative frames.
3. Ask for a specific edit (for example, “remove 01:12–01:15”) or an outcome such as “make this clean and publishable.”
4. Answer focused questions when pacing, captions, emphasis, or another consequential choice is unclear.
5. Review the proposal’s summary, duration change, evidence, assumptions, warnings, and optional JSON diff.
6. Apply or reject the complete proposal. Applied proposals are exactly undoable, and follow-up chat can revise prior edits.
7. Save the project or export the current result as an H.264 MP4.

ctrlB currently supports dead-air and range removal, speed and volume changes, transcript-grounded captions, title cards and lower thirds, restrained evidence-grounded zooms, updating or removing existing edits, project save/open, preview, undo, and MP4 export.

## Install and run

Requirements: a current Node.js/npm environment and Windows for the packaged desktop target.

```sh
npm install
cp .env.example .env
```

Set the shared backend URLs and `CTRLB_PLANNER_CLIENT_TOKEN` in `.env.local`, then run:

```sh
npm run electron:dev
```

Provider keys remain in the shared backend and are not placed in the Electron renderer or Android app. Both planners default to Kimi, while `CTRLB_DESKTOP_AI_PROVIDER=openai` enables the optional desktop OpenAI adapter. Desktop and mobile share the authenticated AssemblyAI transcription route.

## Repository layout

- `apps/desktop/` — Electron, React/Vite, Remotion, desktop tests, and build scripts.
- `apps/mobile/` — native Android/Compose editor and Gradle project.
- `services/backend/` — authenticated AssemblyAI, desktop planner, and mobile Kimi planner implementations.
- `api/` — thin Vercel adapters for the shared backend service.

## Documentation

- [Product vision](docs/ai/ProductVision.md) - footage-specific editorial direction and architecture
- [Architecture](docs/ai/architecture.md) - local Layer 1, cloud planner, and Layer 2 boundary
- [Product tickets](docs/ai/tickets.md) - the single active, blocked, superseded, and completed work ledger
- [Internal visual handbook](docs/human/index.html) — product workflow, evidence model, architecture, and contributor runbook
- [Implementation status](docs/ai/ProjectStatus.md) — verified capabilities and code map

## Current limits

The desktop editor is designed around narrated developer recordings. The current
mobile transcription path still sends selected source media to the streaming
Node backend; this is legacy behavior. Ticket 2 replaces it with locally
extracted compressed-audio jobs so source video remains on the device. Build
the current backend with `services/backend/Dockerfile`, then point both clients
at its HTTPS origin using `CTRLB_BACKEND_URL`. That Render Node deployment serves
`/v1/mobile/plan`, `/v1/desktop/plan`, `/v1/transcribe`, and the authenticated
billing routes; Vercel is not required for these routes.

For a managed deployment, connect this repository to Render and apply the
included `render.yaml` Blueprint. Configure the unsynced secrets in the
Render dashboard, then use the generated `https://<service>.onrender.com` URL as
`CTRLB_BACKEND_URL`.

## Android editor

The Android-first talking-head editor lives in [`apps/mobile/`](apps/mobile/README.md).
