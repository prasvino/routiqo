# Google authentication status

Google is the first login method. Phone OTP is deferred.

## Implemented
- Server verification of Google's signature, issuer, audience/presenter, subject, timestamps and challenge nonce.
- Durable Google-subject-to-account mapping; no automatic linking by email; disabled accounts stay disabled.
- Five-minute one-use login challenges with independently hashed device binding.
- Atomic challenge consumption/account/session issuance, verification outside the transaction.
- Fifteen-minute opaque sessions with hashed credentials, revocation and enabled-account checks.
- Opt-in browser HTTP transport: masked CSRF token, exact Origin, HttpOnly cookies, rate limits, bounded request bodies, no-store responses and bounded expired-row cleanup.

Default preview denies auth endpoints. Enable web-auth only with persistence and google-auth plus external configuration. HTTPS cookies use __Host- names and SameSite=Strict; explicit loopback HTTP development uses unprefixed names. Browser credentials are never stored in localStorage or returned as browser session JSON. Guarded browser journey and routing APIs use this cookie transport.

The separate opt-in native-auth namespace is described by NATIVE_AUTH_HTTP_SPEC.md
and ADR 0020. Native responses intentionally contain opaque credentials and device
bindings; DTO/error output must remain redacted and responses no-store. Strict
bearer authorization has no cookie fallback and does not enable bearer access to
browser journey/routing endpoints. Peer, account and challenge rate gates are
database-backed. Retained predecessor credentials can revoke their session lineage
after rotation, but cannot authenticate ordinary session operations.

## Pending
Real OAuth client/consent/origin setup, native transport and secure-store device verification, deployment/proxy trust, rate-limit and cleanup capacity validation. Web Google UI/proxy, visible-page bounded renewal and recent-auth account deletion are implemented. Sessions expire after 15 minutes without successful renewal, or absolutely 12 hours after Google authentication. Logout revokes the authentication lineage. Account deletion preserves local plans and requires explicit confirmation after fresh Google authentication. No real Google account login has been tested.

Native credential storage has a separate specification in `NATIVE_SESSION_STORAGE_SPEC.md`: a single Expo SecureStore record, bounded opaque-session validation, serialized operations, stale-write rejection and fail-closed recovery. Native response validators normalize bounded challenge/session responses for this vault. These pieces are not yet connected to login or device network transport and do not provide a verified native account. Redirect-safe native networking, challenge-binding lifecycle coordination, Google UI, resource transport and device verification remain pending.

## Specifications
Read GOOGLE_SIGN_IN_SPEC.md, SESSION_EXCHANGE_SPEC.md and WEB_AUTH_SPEC.md under docs/features/auth, and ADRs 0007–0009. Earlier specifications describe the implementation sequence; WEB_AUTH_SPEC.md describes the browser HTTP boundary; NATIVE_AUTH_HTTP_SPEC.md and ADR 0020 describe the separate native boundary. Google subject is the external identity key, not email or phone. Never merge accounts based only on matching contact details.

See SESSION_RENEWAL_SPEC.md, ACCOUNT_DELETION_SPEC.md and ADR 0011 for current lifecycle behavior. Session hashes remain for the 12-hour lineage lifetime plus bounded-cleanup backlog; account deletion removes them immediately.

The unmounted native session restorer now loads the vault and requires a matching
server-verified account response before returning an accountId snapshot. It checks
expiry again after verification and rejects stale work after clear, invalidation
or a newer restore. Local clear is not server logout. Future vault writers must
invalidate restoration before changing credentials, and callers must discard old
verified state while restoring or after failure. See NATIVE_SESSION_RESTORE_SPEC.md.
