# ctrlB Cloud

Shared long-lived backend for ctrlB mobile and desktop.

It owns authentication and authorization, planning, transcription, derivative jobs, the route-local LangGraph video agent, billing state, account lifecycle, catalog ingestion, and the public privacy policy.

ctrlB is now defined as a video-first multimodal editing agent. See [Product definition](docs/PRODUCT.md), [Architecture](docs/ARCHITECTURE.md), and [Roadmap](docs/ROADMAP.md).

Start with the [cloud development guide](docs/DEVELOPMENT.md) before taking a
ticket.

Use the [canonical implementation order](docs/IMPLEMENTATION_ORDER.md) to choose
the next ticket across cloud, desktop and Android.

The request-shaped Gemini path starts with
[the publishable first slice](docs/PUBLISHABLE_FIRST_SLICE.md).

## Run

```powershell
npm install
npm run backend:serve
```

Copy `.env.local.example` to the ignored `.env.local` first.

The service listens on `PORT` (default `8787`). Production deploys through the included Render Blueprint.

## Verify

```powershell
npm test
```

Production fails closed unless required authentication, provider, and Supabase billing configuration is present. Secrets belong in Render or local untracked environment files.

Public wire-contract versions are declared in `contracts/contract-manifest.json`. Clients pin compatible versions rather than copying cloud implementation code.
