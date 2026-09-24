# Native place search, estimates and manual directions

Status: implemented and locally verified, 2026-09-24. Configured-service and
physical-device checks remain in `../../validation/NATIVE_ROUTING_PENDING.md`.

Scope: the next complete Android utility feature after private consent. Reuse
existing guarded routing/place providers and normalized shared models. This is
explicit route planning and manual instruction review, not GPS navigation,
downloaded maps, private route binding or public LIVE. No new provider, dependency,
background location, persistence, purchase or deployment.

## Native HTTP boundary

- Add POST `/api/v1/native/routes/places` and `/api/v1/native/routes` behind
  `ROUTIQO_NATIVE_ROUTING_API_ENABLED=false`, `native-auth & routing` profiles.
  Exact method/path security allowlists; native bearer and one exact account
  header; reject cookies, browser origins/fetch metadata and query strings.
- Strict bounded UTF-8 JSON parser: 20 KiB request; reject duplicate, unknown,
  missing and trailing fields and coercion. Search accepts exactly `query` and
  existing PlaceQuery bounds. Route accepts exactly mode/origin/destination,
  supported mode and two finite JSON numeric coordinates per endpoint; use
  existing RouteRequest validation. Do not accept IDs/geometry/provider overrides.
- Authenticate before provider work. Share existing durable `place-search-account`
  and `routing-account` 20/min budgets with browser calls, plus native guard
  budgets. No automatic retry or DB transaction held over provider work.
- Return existing minimized normalized PlaceResults/RouteResult shapes, with
  redacted DTO diagnostics and `Cache-Control: no-store` on success and failures.
  Exact 200; bodyless 400/401/403/413/415/422/429/503, Retry-After 60 on quotas.
  Sanitize provider, session, rate and storage errors. Default-off denies paths.
  Contracts and generated models must describe both leaves.

## Android transport and account integration

- Add only the two exact POST paths to JS/Kotlin allowlists. Keep TLS/origin,
  redirect and bearer policy. Response bounds: 1 MiB routes, 256 KiB search,
  JSON, strict UTF-8 and exact 200; shared validators strip provider extras.
- Fixed 18-second deadline covers credentials, dispatch, body and validation;
  caller cancellation and abort-ignoring adapters cannot publish late results.
  Copy validated route/search input before awaiting credentials; current session
  checks before dispatch and acceptance. No raw coordinates/query/error logging.
- Provider exposes typed explicit search/calculate methods with current-account,
  foreground/online/deletion/restoration/working guards and session denial cleanup.
  No consent requirement: travel utility remains independent of participation.

## Mounted Android experience

- Separate default-off `EXPO_PUBLIC_ROUTIQO_NATIVE_ROUTING_ENABLED` section in
  Trips after essential journey controls and before private consent/history.
  Verified account required; clear private inputs/results on account loss/change.
  Do not require an active journey. No automatic search/calculation on input,
  mount, reconnect, focus, renewal or timers.
- Explicit starting/destination text search; select a returned match to use its
  copied coordinate/label. Show dataset attribution. No coordinate product labels.
  Driving/walking/cycling choice, explicit Swap and Calculate. No raw location
  request. Text edit clears that endpoint selection/results and prior route;
  mode/swap/selection changes clear stale route association synchronously.
- Bound results to the shared model. Show alternatives, distance/time, provider
  estimate timestamp, no-route/missing-steps outcomes and manual Previous/Next
  instruction review. Selecting the current alternative preserves review position;
  a different alternative starts at its first instruction. No invented maneuvers.
- Preserve the last successful route/selected alternative/instruction during
  failed same-input recalculation, offline loss and temporary same-account renewal.
  Label retained estimates and offline limitations. New inputs clear old results.
  Search/calculation single flight; edits/cancel/offline/background/Trips blur
  synchronously invalidate token and abort; late outcomes cannot mutate state.
  Reconnect/focus never resumes work. No SQLite/outbox/route cache addition.
- Accessible native inputs/buttons, shared tokens, >=48 dp targets, wrapping,
  loading/empty/error/offline/session states, helpful announcements. Bound rendering
  of up to 500 steps through manual review; do not render 500 interactive rows.
  Existing regional map preview remains separate, clearly not a selected-route map.

## Acceptance and evidence

Real HTTP tests with PostgreSQL/native sessions and bounded local provider fixture
cover enabled/default-off, strict requests, account/ambient-auth isolation, shared
quota, redacted failures and normalized routes/search. Native tests cover bounds,
deadline, cancellation, immutable input, session race, exact method paths and
controller transitions/retention/no implicit traffic. Review security independently.
Run full TS/core regressions, types/lint/format/contracts/secrets, Android build,
install/log/screenshot and labeled synthetic emulator UI scenarios including
offline, background, account switch and narrow large text. Remove all fixtures.
Record real OAuth/HTTPS/regional provider/device checks in native routing ledger;
do not claim fixtures prove configured service or production performance.
