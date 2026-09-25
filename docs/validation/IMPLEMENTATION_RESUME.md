# Implementation resume: Phase 1 handoff

Updated 2026-09-25. Replaces the private LIVE handoff, now archived at
[`../archive/validation/IMPLEMENTATION_RESUME.md`](../archive/validation/IMPLEMENTATION_RESUME.md).

## Current state

- The [direction brief](../direction-brief.md) is adopted as
  [`PRODUCT.md`](../PRODUCT.md): Journey, Spots and Ask Ahead, piloting on the
  Pongal exodus along GST Road (Chennai to Trichy/Madurai, plus Kilambakkam).
- Documentation is aligned: [`todo.md`](../../todo.md) and
  [`BUILD_PLAN.md`](../development/BUILD_PLAN.md) follow the five phases;
  superseded LIVE, presence, consent and V3 material is in
  [`docs/archive/`](../archive/README.md). No code changed in this reset.
- Verified behaviour is unchanged and recorded in
  [`BUILD_STATUS.md`](../quality/BUILD_STATUS.md).

### Paused code checkpoint: native private route preparation

Commit `f5c5d7e` ("checkpoint private route preparation pending final QA") holds
native private route preparation and context recovery under ADR 0061 and
`../features/live/NATIVE_ROUTE_PREPARATION_SPEC.md`: an exact GET/POST native
route-context backend leaf, strict Android transport, provider wiring, a
consent/route coordinator, controller and native controls. Independent server and
client flags (`ROUTIQO_NATIVE_LIVE_ROUTE_BINDING_API_ENABLED`,
`EXPO_PUBLIC_ROUTIQO_NATIVE_LIVE_ROUTE_BINDING_ENABLED`) default to false.

What was verified before the pause: full `:core-api:check` (568 tests / 94
suites), focused backend/configuration tests, contract drift, focused UI/controller
tests, mobile TypeScript and affected lint, and native route-context transport
tests. What was **not**: mounted Android QA of the feature, a final Android
rebuild, and the full TypeScript/workspace checks on final source. Temporary QA
fixtures were restored and not committed; ignored `.patch-work` files hold
fixture templates and logs.

Under PRODUCT.md, per-journey consent and private route preparation are
archived: Spots ahead should appear automatically on journey start. Do not
resume the old QA sequence. Whether to keep this checkpoint default-off, reuse
parts of it (for example transport or coordinator patterns) or remove it is a
**code decision for the user**; do not delete or extend it without that decision.

## Next steps: Phase 1 — Foundations

**Deadline (decided 2026-09-25):** the Diwali 2026 dry run (outbound rush 5–7 November)
needs Phase 1 plus the reduced Phase 2 scope by about 1 November: see the
*Diwali dry-run critical path* in [`todo.md`](../../todo.md) and the timeline
in [`BUILD_PLAN.md`](../development/BUILD_PLAN.md). Work outside that path waits
until after Diwali.

Gate: the app installs and runs a full journey on **3 or more physical Android
phones** against hosted staging services.

1. **Google sign-in on Android.** Configure the Web OAuth client (server
   audience) and the Android OAuth client for `com.routiqo.app` with debug and
   release SHA-1 fingerprints ([setup](../development/GOOGLE_OAUTH_SETUP.md)).
   Stand up a staging HTTPS API with `persistence,google-auth,native-auth`. Verify
   sign-in, renewal, logout, account switch and deletion on a device.
2. **Hosted maps and routing.** Import and host corridor-scoped Valhalla, Photon
   and tiles/styles/sprites/glyphs under `/maps/`; fixed origins, bounds and
   redacted logs. Validate against [`NATIVE_ROUTING_PENDING.md`](NATIVE_ROUTING_PENDING.md).
3. **Android Journey map.** Write its spec first (selected route on the map,
   foreground-only "me" during an active journey, a panel slot for Spots ahead,
   offline and permission states), then implement behind a default-off flag.
4. **3+ physical phones.** Build a signed internal test APK; run the full journey
   and the open checks in [`NATIVE_ANDROID_PENDING.md`](NATIVE_ANDROID_PENDING.md),
   [`NATIVE_HISTORY_PENDING.md`](NATIVE_HISTORY_PENDING.md) and
   [`NATIVE_JOURNAL_PENDING.md`](NATIVE_JOURNAL_PENDING.md). Cloud agent sessions
   have no Android SDK or device; record those checks as pending.

## Carried forward to Phase 2 (from the archived V3 trial ledger)

From [`V3_STAGING_TRIAL_PENDING.md`](../archive/validation/V3_STAGING_TRIAL_PENDING.md),
re-scoped from traffic summaries to posts, voice notes and chat. Details in the
[pilot moderation runbook](../development/PILOT_MODERATION_RUNBOOK.md).

| Dependency | Engineering work | External input or review |
|---|---|---|
| Real identity | Verify separate consumer and moderator Google audiences, origins and sessions; two-account browser and device flows | Google project owner configures OAuth clients, approved origins and admin MFA policy; consenting test accounts and devices |
| Moderation | Deploy and exercise the existing default-off admin queue and grant console with real accounts; add report intake for posts/voice/chat and a content hide action; exact retries, finite issue/revoke, cleanup verification | Named moderators and grant administrator, out-of-band trust-root bootstrap, admin OAuth/MFA policy, small named rota with time-boxed grants for the event window, supervision and retention review |
