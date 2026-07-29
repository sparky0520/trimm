# Selective derivative ingestion

`POST /v1/derivatives/jobs` accepts only the `selective_derivative` media
mode. The authorization records:

- the canonical owner, project, and device session;
- a source fingerprint and one or more exact source-time mappings;
- why the range was selected;
- selective-analysis consent and its disclosure version;
- every external provider and covered derivative data class;
- byte, duration, count, cost, and retention limits.

Allowed selection reasons are `request_selected`, `agent_requested`,
`representative_coverage`, `measured_candidate`, and `uncertainty`.
Authorization retries use an idempotency key, and reusing that key with changed
scope or consent fails with `idempotency_conflict`.

Uploads are resumable with `HEAD` and `PUT`, then inspected on `POST
/v1/derivatives/jobs/{id}/complete`. Declared media type is checked against the
actual bytes; source-video containers are rejected. Results and content reuse
are partitioned by owner and project.

`DELETE /v1/derivatives/jobs/{id}` removes uploaded bytes and preserved cached
analysis, clears the result, and retains a bounded deletion tombstone for the
activity record. Automatic expiry and retention cleanup remain in effect.

Optimized proxy and original-source media are not accepted by this route.
Those modes require a separate API and an explicit user opt-in before they can
be implemented.
