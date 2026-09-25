# Private browser stop of an issued signal command

Status: implemented and independently reviewed, 2026-09-19; remains default off.

> **Direction brief (2026-09-25):** Kept; stop/withdraw becomes "delete my post". Web is secondary to Android, and the same behaviour is needed on the native transport. See [PRODUCT.md](../../PRODUCT.md).

## Scope

Add POST `/api/v1/journeys/{id}/signal-commands/{commandId}/stop`, exact body `{}`,
through CatalogSignalService.stopCommand. Reuse the existing signal API flag and
web-auth/routing/persistence profiles. The choice flag must NOT gate stopping an
already issued command: stopping remains possible when new choice interactions
are disabled. With the signal flag absent/false this leaf is denied like existing
withdrawal. No flag activation, new persistence, provider, public output or UI.

Authenticate session and exact single account header, validate canonical nonnil
path IDs and strict bounded UTF-8 empty JSON, then reserve a separate durable
`signal-stop-request` 30/minute quota and call the facade once. Preserve existing
peer quotas, Origin/Fetch-Site, CSRF, media/20KiB body guards and no-store. No
bearer fallback, optional identity, query strings or extra path suffixes.

## Response and recovery

Return exactly `{commandId, status: "stopped", receipt}`. `receipt` is null or
the existing minimized receipt DTO with status withdrawn/superseded, matching the
requested command. It can never be accepted. Original receipt timestamps remain;
no fresh acknowledgment lifetime is invented. Redact wrapper diagnostics.

Success means this command cannot produce a new acceptance after the stop's
authority transaction. A null receipt is NOT evidence that no past acceptance
occurred. Stopping a superseded command does not stop its replacement. Stop is
not physical erasure or public revocation delivery. Unknown/foreign/expired
unavailable state returns generic bodyless409 without revealing which case.
Existing missing journey404, authentication401, browser403, malformed400,
media415, oversized413, quota429/Retry-After 60 and unavailable503 remain.

A lost stop response is uncertain; only an explicit retry with the same command
is permitted. State cleanup can later make a retry unknown. Do not create a new
grant, replay acceptance to manufacture a receipt, or treat failed/aborted stop as
confirmation. The server stop path must not require active sharing or the current
route/catalog for an existing command; real configured facade remains required.

## Client and proxy

Add the exact leaf/method to the existing proxy allowlist with its unchanged 64 KiB
response/timeout bounds. Generate a typed response. Add an explicit browser
function using the existing LIVE 12-second end-to-end deadline including CSRF,
streamed response bounds, cancellation, captured identities, generic errors and
no retries/persistence. Validate exact wrapper fields, stopped status, matching
command and terminal receipt through the existing receipt validator. Retained
receipts may be older than evidence expiry; do not require fresh evidence for stop.

## Acceptance before claiming completion

Use real HTTP/PostgreSQL to stop an unused grant then prove later acceptance
denies, stop an accepted command then replay terminally, repeat stop, and stop
after consent revocation/completion. Verify zero new receipts on grant-only stop,
no budget refunds, owner/journey isolation, unknown IDs, strict empty-body parsing,
UTF-16/32 rejection, CSRF/account/origin/no-store, separate stop request quota,
generic infrastructure errors and disabled-signal denial. Test choice-off with
signal-on permits stop and choice-on with signal-off still denies.

Reuse ADR 0046's transaction race/rollback evidence. Proxy/client tests cover exact
path/method/query/suffix matching, malformed/foreign/active receipt rejection,
older terminal receipt acceptance, cancellation/deadline and no automatic retry
or acceptance fallback. Full checks/builds and independent review are required.
This supplies recovery capability; UI lifecycle, accessible controls and real
OAuth/provider/device verification remain separate work.


## Verified evidence

The 76-test focused backend run and full 431-test/61-suite core check/bootJar
passed. Real PostgreSQL/HTTP tests cover stop/accept/terminal replay and browser
guards. The choice-off security-chain test mocks only the facade to isolate flag
matching; all-on HTTP separately exercises real storage. Browser stop/proxy tests
and the full 420-test/48-file workspace check passed, with a successful web
production build. Independent backend/client/contract review approved. See
BUILD_STATUS.md for later whole-workspace counts as quality checks are added.
No UI, public LIVE, deployment or real OAuth/provider/device verification claimed.
