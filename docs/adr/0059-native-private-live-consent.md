# ADR 0059: Native private LIVE consent

Status: accepted for implementation; no production activation or public privacy
contract is approved. Feature contract: `../features/live/NATIVE_LIVE_CONSENT_SPEC.md`.

## Decision

Expose the existing owner-only privacy consent protocol to the Android client
through a separately default-off native GET/POST leaf. Use the native bearer
boundary and exact account header, with the same PresenceConsentService.read and
submitIntent application interfaces used by the browser. PostgreSQL remains the
authority for ordering, enabled-account/owned-journey checks and shared account
rate budgets. Do not add a second consent store or an actor supplied by the client.

Keep canonical decimal generation strings across the native boundary, including
values beyond JavaScript's safe integer range. Reuse strict intent validation and
bound native response decoding. The UI has its own default-off build configuration,
which controls exposure only; the independent server flag and authentication are
mandatory. Neither flag enables public LIVE or changes publication authorization.

Consent is a deliberate online operation, not a lifecycle-outbox command. Native
controls explicitly check, allow or stop private contributions. Stop uses ADR 0029
revocation precedence even when the last state is unknown. No automatic enable,
retry, rebase, reconnect dispatch or refresh is permitted. Failed/cancelled writes
remain unconfirmed; a subsequent read is not proof that an earlier write cannot
commit. Preserve uncertainty through temporary same-scope lifecycle invalidation,
and require successful explicit Stop before enabling after an uncertain change.

On backgrounding, navigation blur, account changes and session invalidation, cancel
pending requests and discard confirmation. Keep same-account/journey uncertainty
through temporary unavailability without persisting authority. A process restart
does not establish stopped state; reads are explicitly last-observed snapshots.
User-visible language describes private contribution preparation, never global
Ghost enforcement or public discovery protection. Previously retained private
receipts are not deleted by this control.

## Consequences and limits

Android gains a complete consent/reconciliation interaction with existing durable
server authority. Route preparation, Quick Signals, reports and the native public
list remain subsequent features. No lease issuance, GPS collection, precise
location output, new provider, new database schema or realtime/cache publication
is added. Traveller publication retains all existing privacy gates, including the
unapproved ADR 0055 production contract.

Tests must cover disabled routes, owner isolation, exact generations, stop ordering,
shared/separate rate quotas, cancellation and late responses, lifecycle eligibility
and uncertain writes. Synthetic emulator fixtures establish native interaction,
not real OAuth/staging operation. Configured-service and physical-device checks
remain explicit in the native LIVE pending ledger before any activation decision.
