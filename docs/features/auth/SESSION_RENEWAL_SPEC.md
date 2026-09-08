# Bounded session renewal

Active browser sessions may renew their opaque credential during the last five minutes of its 15-minute life. Earlier requests return the existing credential and expiry. A transaction locks and rechecks the session and enabled account, revokes the old credential, and inserts its replacement. Concurrent rotation permits one winner; replay never resurrects a credential. Only hashes persist.

The original Google authentication time is immutable across renewal. Every descendant expires no later than 12 hours after that authentication. Expired, revoked, disabled-account and future-created sessions cannot renew. Inactivity beyond the credential expiry requires Google sign-in again. This is bounded active-session rotation, not a durable refresh-token facility.

Transport uses POST, the existing exact-origin/CSRF policy and HttpOnly cookie. A lost rotation response may require signing in again; do not introduce replay grace that weakens revocation. Fresh authentication for account deletion must use the original authentication timestamp, never the replacement creation timestamp.

Acceptance: PostgreSQL tests cover early no-op, old credential rejection, simultaneous rotation, expiry, disabled account, absolute cap and rollback. HTTP and generated contract coverage precede browser exposure. No live Google credentials are required for isolated tests.
