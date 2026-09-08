# ADR 0011: Bounded browser renewal and account deletion

Status: accepted, 2026-09-08.

Keep opaque session credentials and rotate in their last five minutes, preserving the original Google authentication time. A visible browser page attempts renewal once per minute; hidden pages stop. Fifteen-minute inactivity and a twelve-hour absolute lifetime require Google login again. Web Locks serialize renewals where available; database locks and replay rejection remain authoritative.

Sign-out revokes the authentication lineage, including replacements that raced with the request. The lineage is account ID plus original microsecond authentication time. Two independent Google exchanges at exactly the same microsecond may be signed out together, which is deliberately fail-closed. Keep lineage hashes until the twelve-hour cap, then delete in indexed batches of 100; cleanup backlog may delay physical removal. Never log credentials. Account-before-session locking prevents cross-session deletion/renewal deadlocks.

Account deletion requires original authentication within five minutes and explicit DELETE confirmation tied to the account shown when the dialog opened. Foreign keys cascade account sessions and server journeys. Local plans remain independent. A new sign-in after deletion creates a new account. Existing orphan journey owners block migration for explicit operator resolution.

No long-lived refresh token, native credential transport, live Google consent verification or cloud planning sync is implied. Tests use disposable PostgreSQL and synthetic identity verification; real signature verification has separate tests.
