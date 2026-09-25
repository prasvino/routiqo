# Internal signal command decisions (L0.3b, first slice)

Status: internal grant and decision policy implemented; ADR 0024 remains the
persistence contract. Verification is recorded in BUILD_STATUS.md.
This slice supplies pure grant state and retry decisions. ADRs 0028 and 0033 later
implement durable grants, receipts, quotas and catalog-aware authority; ADR 0035
adds only a separately default-off private browser transport. This pure policy
does not itself provide persistence or public publication.

> **Direction brief (2026-09-25):** Kept: idempotent command grants and retry decisions are infrastructure for Spot signals, posts, voice notes and answers, including offline-queued items under the PRODUCT.md offline rule. Consent-generation binding is retired. See [PRODUCT.md](../../PRODUCT.md).

SignalCommandGrant binds a non-nil server command ID to one SignalAdmission and
UNUSED or CONSUMED state. Reuse the admission's immutable issue/expiry interval;
there is no independent renewal. Consumption is terminal and idempotent. Eligibility
requires UNUSED and a current admission. Missing grants deny; receipt deletion
cannot change grant state. Construction is for trusted server/storage code only;
future issuance must generate unpredictable IDs and enforce database uniqueness.

SignalCommandPolicy evaluates an independently authenticated actor, command ID,
exact submitted fingerprint (journey, anchor, value, consent generation, context,
revision), optional stored receipt/grant, current authority snapshots and server
time. Return one internal decision: NEW_ACCEPTANCE_CANDIDATE, RETAINED_REPLAY,
CONFLICT or DENIED. These are not public response codes or proof of authorization.

Before any replay, require matching authenticated actor and current journey owner
and journey ID. A retained receipt with matching command ID and exact fingerprint
returns RETAINED_REPLAY even after grant expiry, withdrawal, journey completion or
Ghost Mode; this recognizes the original private command outcome only. A retained
receipt with changed fingerprint returns CONFLICT. Cross-owner or mismatched
stored command records deny without exposing existence. Missing required identity,
fingerprint, journey or time denies. Receipt/grant are optional lookup results;
consent/context are required only when evaluating a new acceptance candidate.

An expired receipt cannot return a retained outcome. If its row is still supplied,
deny even with an UNUSED grant: the row proves prior acceptance and contradicts
that grant state. A future-dated receipt also denies. New acceptance with no stored
receipt requires matching unused
grant, its complete admission fingerprint, and SignalAdmissionPolicy over current
authority snapshots. A consumed grant denies even if its receipt is missing or
expired. The policy never creates evidence, consumes grants, renews timestamps or
publishes anything. Repository wiring must later atomically recheck authority,
quotas, consumption and receipt insertion using ADR 0024's transaction design.

Acceptance tests cover exact expiry, null/future clocks, terminal consumption,
invalid identities, redacted diagnostics, wrong actor/command/journey, changed
fingerprints, retained terminal replay without grant, expired-receipt replay with
a consumed unexpired grant, missing grants and changed consent/context/completion.

## Storage integration follow-through

ADR 0025 and JOURNEY_AUTHORITY_TRANSACTION_SPEC.md now implement the account-first
transaction boundary. JdbcSessionStore deletes accounts while holding the account
lock before session locks; JdbcJourneyStore start/completion and owned-journey
callbacks share the account gate. PresenceConsent and LiveRouteContext still have
no durable authority store. A receipt repository alone therefore cannot establish
the complete atomic acceptance boundary.

ADRs 0026–0028 implement durable consent, route context, grants, contribution
slots and receipts under the account-before-journey order with database uniqueness
for missing-row arbitration. Their rollback and revocation/cleanup races are
covered against disposable PostgreSQL. Route provider calls remain outside the
transaction. Those integrations do not change the guarantees of this pure policy.
