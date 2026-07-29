# “Make this video publishable” — first vertical slice

The first implementation slice is available through `POST /v1/agent/runs`.
It is separate from the frozen `/v1/desktop/plan` and `/v1/mobile/plan`
compatibility routes.

## Request

Clients send contract version 1 inventory:

- the user’s intent;
- project and revision identifiers;
- technical source metadata without local paths;
- a request-shaped Layer 1 media index;
- completed derivative evidence job IDs, when continuing.

For a broad request, the index contains representative whole-source coverage,
measured candidates, and uncertainty ranges. The original source is not part of
the request.

## Run

The cloud creates one run and emits safe activity events. Gemini 3.6 Flash
returns an evidence request, clarification, partial result, or typed edit
program. When evidence is requested, the client fulfills the authorized jobs
and calls `POST /v1/agent/runs/{runId}/continue`. The same run may continue as
often as policy permits; there is no evidence-pass counter.

`GET /v1/agent/runs/{runId}` returns the current run snapshot.

## Current boundary

This slice locks the contract and executable loop. Run storage is currently
process-local and must move to the durable store before production. Client
transport and models exist, while media extraction/upload and chat integration
remain the next client-owned steps.

The cloud requires `GEMINI_API_KEY` and pins `GEMINI_MODEL` to
`gemini-3.6-flash`. Legacy Kimi environment variables continue to serve only
the frozen compatibility planners.
