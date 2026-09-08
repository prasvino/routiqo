# Google authentication status

Google is the first login method. Phone OTP is deferred.

## Implemented
- Server verification of Google's signature, issuer, audience/presenter, subject, timestamps and challenge nonce.
- Durable Google-subject-to-account mapping; no automatic linking by email; disabled accounts stay disabled.
- Five-minute one-use login challenges with independently hashed device binding.
- Atomic challenge consumption/account/session issuance, verification outside the transaction.
- Fifteen-minute opaque sessions with hashed credentials, revocation and enabled-account checks.
- Opt-in browser HTTP transport: masked CSRF token, exact Origin, HttpOnly cookies, rate limits, bounded request bodies, no-store responses and bounded expired-row cleanup.

Default preview denies auth endpoints. Enable web-auth only with persistence and google-auth plus external configuration. HTTPS cookies use __Host- names and SameSite=Strict; explicit loopback HTTP development uses unprefixed names. No credential is stored in localStorage or returned as session JSON. All other protected APIs remain denied.

## Pending
Real OAuth client/consent/origin setup, native secure storage/transport, deployment/proxy trust, rate-limit and cleanup capacity validation. Web Google UI/proxy, visible-page bounded renewal and recent-auth account deletion are implemented. Sessions expire after 15 minutes without successful renewal, or absolutely 12 hours after Google authentication. Logout revokes the authentication lineage. Account deletion preserves local plans and requires explicit confirmation after fresh Google authentication. No real Google account login has been tested.

## Specifications
Read GOOGLE_SIGN_IN_SPEC.md, SESSION_EXCHANGE_SPEC.md and WEB_AUTH_SPEC.md under docs/features/auth, and ADRs 0007–0009. Earlier specifications describe the implementation sequence; WEB_AUTH_SPEC.md describes the current HTTP boundary. Google subject is the external identity key, not email or phone. Never merge accounts based only on matching contact details.

See SESSION_RENEWAL_SPEC.md, ACCOUNT_DELETION_SPEC.md and ADR 0011 for current lifecycle behavior. Session hashes remain for the 12-hour lineage lifetime plus bounded-cleanup backlog; account deletion removes them immediately.
