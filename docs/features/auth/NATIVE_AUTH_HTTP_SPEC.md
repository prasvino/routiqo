# Native authentication HTTP boundary

Implement an opt-in `native-auth` transport under `/api/v1/native/auth`, requiring
the existing persistence and Google verifier services. Default preview continues
to deny these routes. Reuse GoogleSessionService for challenge exchange, bounded
rotation, account deletion and lineage revocation; no new identity or session table.

Endpoints: POST google/challenge returns id, nonce, binding, expiresAt;
POST google/exchange accepts challengeId, binding, idToken and returns accountId,
credential, expiresAt. GET session returns accountId. POST session/renew returns
accountId, credential, expiresAt. POST logout revokes the supplied credential;
POST account/delete requires confirmation DELETE and matching accountId and uses
the existing recent-auth requirement. Remaining POST endpoints require `{}` JSON.

Only these native endpoints return credentials in JSON. Never expose them in web
responses, URLs, logs or errors. Session operations require exactly one Authorization
header with `Bearer ` followed by exactly 43 URL-safe characters. Challenge/exchange
do not accept Authorization. Reject Cookie, Origin, Sec-Fetch-Site and URL query
strings on this namespace. These exclusions are transport separation, not client
authentication: possession/verification of the opaque credential authorizes access.
Disable CSRF only in this isolated native filter chain; browser CSRF remains intact.
No CORS permission, redirect or browser cookie fallback. No native resource access
to browser journey/routing controllers is introduced in this phase.

Every response is no-store. Bound POST bodies to 20 KiB before parsing and require
application/json. Validate JSON types, duplicate/unknown fields and exact expected
keys. Bound Google ID tokens to 16,384 characters. Use existing PostgreSQL rate
limiting and cleanup for either auth profile, with native-specific rate categories
and the existing required external rate secret. No process-local security counters.
Profiles may run together without duplicate beans or weaker browser behavior.
In addition to socket-peer limits, authenticated operations share a stable
account-keyed 120/minute limit so session rotation or a changed peer cannot reset
their allowance. Exchange also has a challenge-ID-keyed 20/minute limit before
external token verification. Hash these keys through the existing rate gate with
distinct fixed categories; never key or log the raw bearer/binding/Google token.

Future native client transport must use a fixed configured HTTPS origin (no credentials,
query, fragment or path), bounded timeout/response sizes, omitted cookies, no
redirect following, minimal validated responses and generic redacted errors.
This slice adds response validators only; no production UI or network client is
mounted. React Native's built-in fetch has documented redirect limitations, so
setting a JavaScript redirect option is not proof of redirect safety. An enforceable
native adapter and device verification are required before sending credentials.
Credential persistence
must use the native session vault; challenge binding and Google ID tokens must not
enter backups, logs or planning storage. Full native sign-in coordination, Google
UI, journey transport and real device verification follow this boundary.

Verification: actual HTTP tests with disposable PostgreSQL and synthetic verifier
cover exchange/replay, wrong binding, bearer rejection, cookie/origin isolation,
renew/logout/deletion, denied unknown routes/methods, body limits, rates, and profile
coexistence. Test response validation and redaction now; test transport timeout/cancel,
cookie omission, fixed URL and redirect enforcement when the adapter is implemented.
Independent security review required.
## Logout during rotation

Logout accepts a syntactically valid retained predecessor credential for revocation
only. A narrowly named revocation-account lookup chooses the stable account rate
key without authorizing session reads or other operations. Existing revoke locks
and invalidates the full retained session lineage. Unknown/deleted credentials
perform no mutation and return idempotent 204 after the peer rate gate. Normal
session/renew/delete endpoints still require active authentication. Test a lost
renewal response: logout with the predecessor must invalidate its live successor,
remain retryable, and leave an unrelated sign-in lineage valid.
