# ctrlb.ai

Config-driven video editor. AI agent proposes diffs to `config.json`; preview and timeline derive from it. Electron + React 19 + Vite + OpenAI.

**Landing page:** [sparky0520.github.io/ctrlb-app](https://sparky0520.github.io/ctrlb-app/)

## Quick start

```sh
cp .env.example .env   # add OPENAI_API_KEY
npm install
npm run electron:dev   # Vite + Electron
```

## Release

```sh
git tag v0.1.0
git push origin v0.1.0
```

The GitHub Actions workflow builds the `.exe` and publishes it to GitHub Releases automatically.

## Docs

- [`docs/human/README.html`](docs/human/README.html) — visual project overview (open in browser)
- [`docs/ai/Architecture.md`](docs/ai/Architecture.md) — state shape, edit flows, file map
- [`docs/ai/Design.md`](docs/ai/Design.md) — component specs, design tokens
- [`docs/ai/Tickets.md`](docs/ai/Tickets.md) — open work items
- [`docs/ai/AGENTS.md`](docs/ai/AGENTS.md) — invariants, AI agent rules, definition of done
