# ctrlb.ai

Config-driven video editor. AI agent proposes diffs to `config.json`; preview and timeline derive from it. Electron + React 19 + Vite + OpenAI.

## Quick start

```sh
cp .env.example .env   # add OPENAI_API_KEY
npm install
npm run electron:dev   # Vite + Electron
```

## Docs

- [`README.html`](README.html) — visual project overview (open in browser)
- [`docs/Architecture.md`](docs/Architecture.md) — state shape, edit flows, file map
- [`docs/Design.md`](docs/Design.md) — component specs, design tokens
- [`docs/Tickets.md`](docs/Tickets.md) — open work items
- [`AGENTS.md`](AGENTS.md) — invariants, AI agent rules, definition of done
