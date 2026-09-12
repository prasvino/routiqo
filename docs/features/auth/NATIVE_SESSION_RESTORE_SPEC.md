# Native session restoration

Restore is a server-verified snapshot, not offline authentication. Load the existing
single session vault and pass its credential only to an injected verification
adapter. Normalize the response with readNativeAccount and require the returned
account to equal the stored account. Recheck the stored session expiry and bounded
clock after verification. Return only accountId; never expose the credential or
trust local account metadata as a verified journey partition.

Each restore supersedes and aborts the previous attempt. Invalidate on account-flow
or lifecycle changes; late success or failure must not expose an account. A failed
request, invalid identity, expired response, corrupt storage or invalid clock returns
a fixed error without provider details. Empty or already-expired vault state returns
null. No cached identity fallback on network errors. Transport must enforce bounded
timeouts, response size, TLS origin and no redirects/cookies before it is mounted.

clearLocalSession invalidates pending restoration before calling vault.clear. It
removes only local credentials; it is not server logout or account deletion and
does not retire local journey data. Removal failures remain visible and retryable
through the vault's existing fail-closed behavior. Do not wrap native vault calls
in a timeout or bypass their serialization.

This phase has no production adapter or UI mounting. Future callers must discard
previous verified-account state when restoration begins, fails or is invalidated;
the returned snapshot is not a perpetual authentication grant. All protected
resource requests still require server authorization. Native Google sign-in,
renewal and coordinated server logout are separate work.

All future writers to the shared vault must invalidate this restorer synchronously
before beginning sign-in, renewal, account switch or deletion. The restorer cannot
observe independent vault writes during network verification. It deliberately owns
no cached verified-account state; callers must apply the lifecycle rules above.

Test valid restoration, empty vault, mismatched/malformed responses, invalid or
expired clocks, delayed loads and network results superseded by restoration or
clear, adapter abort signaling, storage failures and redaction. Review against
T01/T02/T12/T13/T19 before connecting a real credential adapter.
