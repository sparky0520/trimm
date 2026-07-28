# ctrlB Cloud

Shared long-lived backend for ctrlB mobile and desktop.

It owns authentication and authorization, planning, transcription, derivative jobs, Layer 2 orchestration, billing state, account lifecycle, catalog ingestion, and the public privacy policy.

## Run

```powershell
npm install
npm run backend:serve
```

The service listens on `PORT` (default `8787`). Production deploys through the included Render Blueprint.

## Verify

```powershell
npm test
```

Production fails closed unless required authentication, provider, and Supabase billing configuration is present. Secrets belong in Render or local untracked environment files.
