> **Archived 2026-09-25.** Per-journey consent and private LIVE contribution flows, replaced by one Spot-passage opt-in, simple Ghost Mode and public Spot signals. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# Native LIVE implementation and validation ledger

Updated: 2026-09-24.

## Current phase

Native private consent check/allow/stop is implemented under
`docs/features/live/NATIVE_LIVE_CONSENT_SPEC.md` and ADR 0059. Server and build
exposure default off. This phase does not implement the public LIVE list.

Verification: 671 TypeScript tests / 85 files and 555 Java tests / 90 suites
passed, alongside formatting, all six typechecks, lint, generated contracts and
secret scan. Independent backend/transport review passed after correcting a
credential-await dispatch race. Android debug build passed (1m45s; 467 tasks,
21 executed), installed and rendered Home without fatal startup or ReactNativeJS
errors in the captured log. Labeled synthetic-account emulator QA exercised the
actual panel/controller, uncertainty recovery, offline/renewal/navigation,
background cancellation, account isolation and 360 dp/130% text. See
[evidence](../quality/evidence/native-live-consent-2026-09-24/README.md).
These checks do not replace the configured-service and device gates below.

## Subsequent implementation

- Native explicit route preparation and context recovery are implemented locally
  but **paused on 2026-09-24, with a user-authorized checkpoint commit**. See
  [resume handoff](IMPLEMENTATION_RESUME.md) for completed checks and remaining
  verification. The contract is `docs/features/live/NATIVE_ROUTE_PREPARATION_SPEC.md`
  and ADR 0061; this is not a completed feature until verification and commit.
  Native route planning/manual directions are committed separately; see
  `NATIVE_ROUTING_PENDING.md` for their configured-provider gates.
- Native Quick Signal choice/issue/acceptance and exact receipt/stop recovery.
- Native list/report experience for the separately authorized evidence projection.
  Do not conflate official-provider alerts with traveller-derived publication.
- Public-release gates in `PUBLIC_LIVE_PENDING.md` remain binding; public
  production activation is not authorized by this implementation request.

## Human-dependent validation

- Configure real native OAuth and staging HTTPS using `NATIVE_ANDROID_PENDING.md`.
  In a controlled staging build, explicitly enable the native consent server and
  client exposure settings; leave public publication disabled.
- Use two real staging accounts to check owner isolation and session/account
  changes, including sign-out, renewal, expiry and account deletion.
- Exercise explicit allow/stop, response loss, offline/background interruption,
  Android notification-shade focus loss, Trips navigation, refresh and pending
  journey completion. An unconfirmed stop is never privacy-protection success.
- Verify browser/native concurrent operations obey the same consent generation
  and account budgets, including stale enable after a successful stop.
- For private route preparation, supply the reviewed regional anchor catalog and
  existing resolver/provider configuration. Explicitly enable the independent
  native route-binding API/client gates in controlled staging alongside native
  consent and route planning. Confirm lost-response recovery, exact-context
  replacement, expiry, completion and concurrent Stop while the provider is busy.
  A read is only an observation; it cannot establish that a prior bind was undone.
- Check TalkBack, switch/keyboard navigation, large text and reduced motion on a
  representative physical Android device. Record actual device/network results;
  emulator fixtures are not a configured-service or physical-device substitute.

No OAuth credentials, administrator action or production activation has been
requested from the user for the implementation work itself.
