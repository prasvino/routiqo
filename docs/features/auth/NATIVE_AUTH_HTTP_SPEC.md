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
No CORS permission, redirect or browser cookie fallback. The separate native
journey controller uses the same verified bearer and explicit owner context;
browser journey/routing controllers are not exposed to native credentials.

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
The Android development client now mounts an OkHttp-backed Expo module with fixed
HTTPS origin/path checks, disabled redirects/cookies, bounded payloads and timeouts;
the response validators and SecureStore session vault are used by native sign-in,
renewal, logout and owner journey transport. React Native's built-in fetch is not
used for credentials. Challenge binding and Google ID tokens must not enter backups,
logs or planning storage. The native journey resource paths have their own strict
bearer/account boundary, with no browser-cookie fallback. Real-device verification
with staging OAuth/TLS remains a release gate, not an implementation claim.

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

Native response validation rejects the nil UUID for both account and challenge
identities. Canonical non-nil UUIDs remain supported without assuming one UUID
version. The same account invariant applies to secure-vault reads and commits;
a malformed stored identity cannot be restored or sent to a verification adapter.
Canonical matching consumes the entire response string, including UUIDs, opaque secrets and timestamps. Trailing line terminators or whitespace remain invalid.
