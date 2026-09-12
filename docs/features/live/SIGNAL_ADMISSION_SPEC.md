# Signal admission policy slice (L0.2a)

Status: internal models and policy implemented and tested; no public Live admission or evidence
ingestion. ADR 0023 owns the route-context/admission decision. This feature spec
owns concrete model acceptance and regression cases.

LiveRouteContext has non-null, non-nil context/actor/journey UUIDs, a nonnegative
revision and 1–128 distinct non-null, non-nil anchor UUIDs. Defensively copy the
set. SignalAdmission binds non-nil actor/journey/context/anchor UUIDs, nonnegative
route and consent revisions, nonempty closed category permissions and a positive
issued/expiry interval no longer than 90 seconds. Categories must be immutable;
null entries are invalid. No coordinates or free-text input. Both models redact
their string representations and validation errors without raw exception causes.

SignalAdmissionPolicy belongs to the application layer. Given the authenticated
actor ID from server authentication (never a body-supplied actor) and current server
Journey, PresenceConsent, LiveRouteContext, admission, signal value and time,
deny missing values, an admission for a different authenticated actor,
nonowned/inactive journeys, time before journey start,
admission before journey start, future/expired admissions, mismatched actor/journey,
changed route context/revision, missing registered anchor, revoked or ended consent,
mismatched consent generation and a category not permitted by the admission.
Exactly at issuedAt is valid and exactly at expiresAt is invalid. Values within
the allowed category may differ; per-command replay still uses QuickSignal's exact
value match. No write, clock read, membership lookup or public DTO is produced.

These arguments are trusted snapshots from future authority interfaces; a caller
cannot create authorization by deserializing them from a request. The pure policy
does not make their reads atomic, prevent forged public credentials, register
routes, prove presence or authorize aggregate publication. Current consent permits
new submissions only while sharing and the journey are active. Existing accepted
evidence requires the separate withdrawal/projection policy.

Tests: valid admission; all actor/journey/context/revision mismatch paths; changed
anchor/category; Ghost Mode then re-enable; journey completion; issued/expiry and
journey-start boundaries; absent inputs; collection copy/mutation; empty/oversized/
invalid sets; nonpositive/overlong/extreme time spans and redaction. Full Java
architecture/check and build remain required. No live GPS/provider data in tests.
