# Native routing implementation and validation ledger

Updated: 2026-09-24.

## Current feature

Explicit native place search, route estimates and manual directions are implemented
under `docs/features/journey/NATIVE_ROUTE_PLANNING_SPEC.md` and ADR 0060.
Independent server/client exposure flags default off. Loaded directions remain in
memory through connection loss; there is no downloaded navigation or route cache.

Verification passed: 689 TypeScript tests / 89 files, 560 Java tests / 92 suites,
all six typechecks, lint, formatting, generated contracts and secret scan. Independent
backend/transport review passed after strengthening the configured default-off test.
Android debug build/install passed (1m41s; 467 tasks, 21 executed). Labeled synthetic
emulator checks cover explicit search/selection/calculation, alternatives/manual
steps, empty/failure outcomes, offline/renewal/navigation retention, account clearing
and 360 dp/130% text. A Hermes-only cancellation failure found during QA was fixed
across native account, journal, consent and routing; tests remove DOMException and
the final emulator held-request/background trial produced no new runtime errors.
All temporary fixtures and display overrides were removed. See
[evidence](../quality/evidence/native-route-planning-2026-09-24/README.md).

## Human-dependent checks

- Configure native OAuth and staging HTTPS according to `NATIVE_ANDROID_PENDING.md`.
  Enable native routing only in a controlled configured build and service.
- Verify the regional Photon/Valhalla service, attribution and coverage with the
  actual deployment, including unsupported-area and provider-unavailable responses.
  No provider deployment or public fallback is authorized by this feature.
- With real sessions, confirm account switch/expiry/sign-out clears private route
  views and browser/native requests share the same account budgets.
- Check real Android keyboard, TalkBack, large text, focus/background transitions
  and poor/intermittent networks on representative physical devices. Record actual
  device/memory/latency; synthetic emulator checks do not establish performance.

## Subsequent features

- Native private route preparation/recovery, then Quick Signal controls and the
  separately authorized LIVE list/reporting projection.
- Selected-route map rendering, explicit location permission, on-device navigation
  and downloaded maps need their own complete implementation/validation phases.
  Loaded manual instructions are not turn-by-turn guidance or offline route graphs.

No credentials, administrator step or production activation is needed for local
implementation and bounded fixture testing.
