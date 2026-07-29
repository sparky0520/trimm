# Cloud development

## Scope

Work here only when the change owns shared server behavior: authentication,
agent orchestration, derivatives, transcription, canonical contracts, durable
state, billing, account lifecycle, or the operated audio catalog.

Android UI/rendering belongs in `ctrlb-mobile`. Electron/Remotion behavior
belongs in `ctrlb-desktop`.

## Start

1. Copy `.env.local.example` to `.env.local`.
2. Fill only the services required by the feature being developed.
3. Run `npm install`.
4. Run `npm test`.
5. Run `npm run backend:serve`.

The server loads `.env.local` before `.env`. Do not put provider credentials in
either client repository.

## Route lifecycle

- `/v1/desktop/plan` and `/v1/mobile/plan` are frozen legacy v1 routes.
- `/v1/mobile/orchestrate` is the current request-shaped agent foundation.
- New Gemini agent work belongs in the persistent agent-run architecture in
  issues #1–#4, not in the frozen v1 handlers.

## Contracts

`contracts/contract-manifest.json` is the published compatibility manifest.
Breaking changes require a new explicit version and coordinated adoption
tickets in both client repositories.

## Definition of done

- server behavior has bounded validation and authorization tests;
- no source paths, secrets, executable commands, or raw hidden reasoning cross
  a public contract;
- retries, ownership, retention, and failure behavior are explicit;
- `npm test` passes;
- relevant issue acceptance criteria are updated.
