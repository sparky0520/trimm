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

Set `OPENAI_API_KEY` and `ASSEMBLYAI_API_KEY` in `.env`, then run:

```sh
npm run electron:dev
```

The keys are loaded only by the Electron main process. They are not placed in the renderer or production web assets. AI planning uses OpenAI and audio transcription uses AssemblyAI; see [Media analysis and privacy](docs/user-guide.md#media-analysis-and-privacy) before importing sensitive footage.

## Documentation

- [User guide](docs/user-guide.md) — questions, proposal review, privacy, compatibility, and limitations
- [Developer guide](docs/developer-guide.md) — schema v1, edit-operation contracts, architecture, and test commands
- [Implementation status](docs/ai/ProjectStatus.md) — verified capabilities and code map

## Current limits

ctrlB supports one source video per project and is designed around narrated developer recordings. It does not currently provide multi-camera editing, a timeline/inspector, complex motion graphics, automatic B-roll, generative media, music selection, style imitation, collaboration, cloud sharing, or mobile workflows. Legacy unversioned project files are rejected rather than migrated. AssemblyAI transcription accepts local media files up to 2.2 GB.

## Android editor

An Android-first talking-head editor is under active development in [`android/`](android/README.md). It contains the native Compose workflow, versioned operation model, on-device analysis boundaries, conservative fumble detection, capability-aware export planning, and privacy contract while leaving the desktop application intact.
