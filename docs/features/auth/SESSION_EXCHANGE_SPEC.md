# Google challenge and session exchange

## Historical internal scope (superseded transport and renewal details)
Connect verified Google identity to a durable one-time challenge and a short-lived opaque Routiqo session. No public HTTP endpoint yet: same-origin/CSRF controls, rate limits and client cookie transport remain required before exposure. No refresh credential is introduced in this slice; sessions expire after 15 minutes and require reauthentication until rotation is implemented.

## Model
Challenge: random UUID, 256-bit nonce, SHA-256 digest of independent 256-bit browser/device binding secret, created/expiry timestamps, consumed timestamp. Lifetime five minutes. Return nonce and binding once to the eventual transport. Nonce is supplied to Google; binding belongs in an HttpOnly same-site browser cookie or protected native storage, not in the Google request.

Session: random 256-bit opaque credential; only SHA-256 digest persists, along with account UUID, creation/expiry and revocation. Lifetime 15 minutes, no sliding extension. Session lookup joins enabled account state; disabling an account immediately rejects its sessions. No tokens or bindings in logs or exception details.

## Exchange
Read a valid unconsumed challenge using UUID and binding digest. Verify Google token against its stored nonce outside any transaction. Then lock/recheck challenge, resolve verified account, mark challenge consumed and insert session in one transaction. Replays and simultaneous exchanges allow exactly one winner. Failure rolls back both consumption and account/session changes. A credential is returned only after commit.

## Verification and gates
Use disposable PostgreSQL tests for replay, parallel exchange, expiry, wrong binding, verifier failure, transaction rollback, hashed storage, disabled-account rejection and idempotent revocation. Existing signed-token tests cover the real Google verifier; session orchestration tests supply a deterministic test verifier only.

Before public launch: bounded challenge creation/rate limiting, CSRF/origin validation, secure cookie/native storage transport, refresh rotation or explicit reauthentication UX, account deletion and bounded expired-row cleanup, OpenAPI and end-to-end Google consent/client configuration. No live login or cloud sync is claimed yet.

Current additions: WEB_AUTH_SPEC.md and GOOGLE_WEB_UI_SPEC.md expose the guarded browser flow. SESSION_RENEWAL_SPEC.md and ACCOUNT_DELETION_SPEC.md supersede the no-renewal/deletion-pending statements above. ADR 0011 documents lineage revocation and retention.
