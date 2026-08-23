# Authentication rollout and account lifecycle

Owner: ctrlB production operations  
Bootstrap removal date: 2026-08-31

## Account behavior

- Email verification, password/account recovery, and linked identity verification are provider-hosted. ctrlB accepts only the resulting verified canonical subject.
- `GET /v1/account/export` exports bounded identity metadata plus ctrlB-owned remote records.
- `POST /v1/account/sign-out-all` revokes every active provider session.
- `DELETE /v1/account/delete` revokes sessions, removes provider connections, removes ctrlB remote data, and finally deletes the identity. Its stable operation ID makes retries resume safely after partial failure.
- Audit records retain canonical user ID, action, outcome, operation/request ID, and timestamp for 90 days. Billing records, once introduced, follow the separately approved statutory retention policy. Tokens, profile metadata, and private media are never retained in lifecycle audit events.
- A disabled provider account cannot mint a usable session; an already-issued token is rejected through provider revocation and backend revocation checks.

The current repository has no durable remote project database. The volatile adapter truthfully exports no projects and is not acceptable once durable remote user data is introduced; replace it with a transactional store that implements the same deletion-progress contract first.

## Development data policy

Bootstrap identities are deliberately non-user identities and cannot call lifecycle operations. Existing bootstrap-created resources remain development-only and must be explicitly discarded. They must never be claimed, migrated, or silently assigned to a Clerk user. Test tenants may be reset after exporting fixtures that contain no user data.

## Staging release gate

Record evidence for each item before production promotion:

1. Verify email, recovery, identity linking, disabled-user handling, sign-out-all, export, and deletion in the staging Clerk tenant.
2. Exercise signing-key rotation with overlap, provider outage without bootstrap fallback, configured JWT clock tolerance, refresh-token failure, and a forced partial deletion followed by retry.
3. Run Android, packaged desktop, and backend flows for two users. Prove project isolation, account switching, concurrent sessions, revocation recovery, and deleted-user rejection.
4. Alert on authentication error-rate, JWKS/provider failures, deletion failures, and unusual lifecycle volume. Dashboards must use request IDs and canonical subjects, never tokens.
5. Confirm support can locate a deletion operation, retry a failed operation, revoke all sessions, and explain retained audit/billing records.

## Rollout and rollback

Promote the staging issuer, audience, authorized parties, redirects, and secrets through the deployment manager. Start with internal users, then a bounded cohort, then all clients. Roll back clients and routing to the prior signed release if authentication failures breach the agreed alert threshold; do not restore bootstrap access. During a provider outage, local projects remain available and remote actions show a retryable authentication error.

On 2026-08-31, remove `CTRLB_PLANNER_CLIENT_TOKEN`, `CTRLB_ALLOW_BOOTSTRAP_AUTH`, and the bootstrap branch after production monitoring shows no bootstrap traffic for 14 days. The operations owner records completion in the release log.
