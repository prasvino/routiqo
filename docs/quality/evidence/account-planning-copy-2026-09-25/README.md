# Account planning copy evidence (ADR 0062)

Captured on 2026-09-25 from a production `next build` / `next start` of `apps/web` with `NEXT_PUBLIC_ROUTIQO_PLANNING_BACKUP_UI_ENABLED=true`, in Playwright Chromium.

**All API responses are synthetic.** Playwright intercepted every `/api/v1/**` request and answered from an in-memory stand-in that implements the real compare-and-swap, exact-replay and delete semantics. There was no real Google sign-in, core API or database. Separately, the real API was tested over HTTP against disposable PostgreSQL (`BrowserPlanningHttpTest`, `PlanningPersistenceTest`). This is not staging, real OAuth or physical-device evidence.

## Interaction evidence

- **`01-not-checked.png`:** the panel mounts for a verified account. No planning request was made before the explicit check (count recorded: 0).
- **`02-no-account-copy.png`, `03-saved-first-copy.png`:** the explicit check shows that no copy exists. The first save needs no confirmation and reports what was saved. The device then matches the copy.
- **`04-other-device-checked.png`, `05-added-to-device.png`:** a second browser context acts as another device.
  - It sees the account copy.
  - "Add account plans to this device" merges it: the local plan is kept and 2 plans plus 1 place are added.
- **`06-replace-confirmation.png`:** replacing an existing copy asks first and shows both counts. It also suggests adding the account plans before saving.
- **`07-conflict-requires-check.png`:** the first device's stale replace gets a 409. The view is cleared, and the only action left is checking again.
- **`08-uncertain-retry.png`:** the synthetic server committed a write but answered 503.
  - The UI says the save wasn't confirmed and offers **Retry save**, with the new-save button disabled.
  - The retry resent the identical mutation and was replayed. The server version moved 2 → 3, exactly one new version.
- **`09-offline.png`:** in offline mode the account actions are disabled with an explanation. Local data stays available.
- **`10-remove-confirmation.png`, `11-removed.png`:** removal needs a confirmation. It removes only the account copy, and the device counts are unchanged.
- **`12-keyboard-focus.png`:** keyboard focus shows a visible ring. Pressing Enter on the focused check button performed the check.
- **`13-narrow-large-text.png`, `14-narrow-large-text-saved.png`:** checked at 360 px width with 130% page zoom and `prefers-reduced-motion: reduce`. The status cards stack, buttons take full width, and there is no horizontal overflow (measured).

## Flags off

A separate default production build (flags unset) with a synthetic signed-in session was also checked:
- no "Plans on your account" panel;
- the original "Cloud sync isn't available yet" copy;
- the original privacy wording;
- zero planning requests.

## Console

The only console errors were:
- the deliberate synthetic 409 and 503 responses;
- a 404 from the existing session-maintenance renewal call (`/api/v1/auth/session/renew`), which this synthetic harness does not implement.

The feature itself produced no other console errors.

## Not verified

- Real Google OAuth, HTTPS staging and the core API behind the Next proxy.
- Two real devices.
- Screen-reader announcements.
- Native Android, since no native controls are part of this slice.
