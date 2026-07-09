# ctrlb

**Cursor for video editing** - AI-powered video editor with a minimal interface. Just a video preview (like a code editor window) and a chatbox (like Cursor's AI agent chat).

## Quick start

```sh
cp .env.example .env   # add OPENAI_API_KEY
npm install
npm run electron:dev   # Vite + Electron
```

## How it works

1. **Import** a video clip via the Import button
2. **Chat** with the AI agent to make edits: "Trim dead air", "Add text overlay", "Speed up section"
3. The AI proposes edits to the config, and you can **Apply** or **Reject** them
4. **Save** your project to continue editing later

## Features

- Video preview with play/pause/scrub controls
- AI chat interface for natural language video editing
- Inline diff cards showing proposed changes
- Project save/load functionality
- Basic video editing: trim, speed, zoom, text overlays, color cards

## Export

Currently in development - video export functionality coming soon.