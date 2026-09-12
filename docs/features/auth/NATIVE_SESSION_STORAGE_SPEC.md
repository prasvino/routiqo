# Native session credential storage

This phase adds the secure storage prerequisite for native authentication. It does
not mount a sign-in UI, send credentials, enable new HTTP endpoints or treat local
metadata as a verified account. Native transport must still verify sessions with
the server before selecting a journey partition.

## Data and storage boundary

Use the Expo SDK 55-compatible pinned `expo-secure-store` package. A single fixed
key stores one version-one JSON record containing only `accountId`, `credential`
and integer-millisecond `expiresAt`, plus `version`. Account IDs are canonical
lowercase UUIDs; credentials match the server's 43-character URL-safe opaque format.
Reject unknown fields, unsupported versions, invalid values and records over
1,024 UTF-8 bytes. Never put credentials in SQLite, planning backups, URLs or logs.

Only the production singleton owns this key. Its injected test driver uses the
same serialization and validation code. Use a fixed Keychain service and
`WHEN_UNLOCKED_THIS_DEVICE_ONLY`; no biometric requirement or prompt. Keep the
SecureStore Android backup exclusion plugin enabled. Availability/read/write/delete
failures return generic errors without the native error's cause or payload.

Commit accepts only a future expiry at most 15 minutes from the supplied clock.
Recheck that bound when a queued write executes and after native completion. If
the record becomes expired or invalid during the write, remove it before releasing
the queue and report failure; a slow native call must not report a usable session.
Load rejects a future expiry beyond the same 15-minute bound (including clock
rollback) and returns null for an expired session. It does not
renew, delete an expired record or authorize offline access. Store no reusable
Google ID token. Challenge-binding storage will accompany the native exchange flow.

## Ordering and failure behavior

Serialize every native operation through one vault. `beginWrite` synchronously
issues an opaque, instance-bound, one-use ticket and invalidates older tickets.
Commit checks its ticket before writing. A new attempt or clear that invalidates a
write already executing causes its stored result to be removed before later queued
operations proceed. Never claim that an obsolete session was accepted.

`clear` invalidates tickets synchronously and queues removal after any running
operation. Late sign-in/renewal results cannot recreate a cleared credential.
It does not delete journey drafts, retire an account, revoke a server session or
claim complete logout: the future auth coordinator must perform those distinct
steps as appropriate. A load begun before generation changes cannot return secrets.

An ambiguous native failure or corrupt stored record blocks subsequent loads and
commits in that vault instance until a successful clear. Surface the failure and
require recovery; do not silently downgrade to signed-out or use an in-memory
credential. Explicit clear can be retried. Do not impose a Promise timeout that
would release serialization while an uncancellable native write is still running.

## Verification and release gates

Test serialized success/reopen, invalid and expired records, foreign/reused/stale
tickets, delayed writes/loads racing clear, native failures and redacted errors.
Test the actual SecureStore adapter's fixed key/options with a mocked native module.
Verify types, lint, Android export and generated backup configuration. Native
Keystore/Keychain execution, reinstall behavior and physical-device background/lock
testing remain release gates; mocks and JavaScript export do not verify them.

Threats: T01 (credentials/session replay), T02 (account confusion), T12 (leakage),
T13 (races) and T19 (deletion). This boundary assumes one application JS runtime;
do not share the key with another runtime or background process without a reviewed
cross-process coordination design.
