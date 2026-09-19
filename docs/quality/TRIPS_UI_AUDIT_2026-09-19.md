# Trips accessibility refinement — 2026-09-19

Scope: signed-out local planning at `/trips`, using Routiqo's existing design
system and the project-local `routiqo-ui-quality` / Impeccable audit and polish
checks. This is a focused rendered audit, not a full accessibility certification.

## Confirmed findings and fixes

| Priority | Evidence | Change |
|---|---|---|
| P2 | At a 390 px viewport, commute weekday buttons measured about 42 × 43 CSS px, below the product's 44–48 target. | Weekday controls now have a 44 px minimum in both dimensions; the narrow layout uses four columns and wraps the remaining days in calendar order. |
| P2 | Accessibility headings read origin and destination without their relationship; the visible arrow had no equivalent text. | Added visually hidden “to” and hid the decorative arrow from assistive technology. The rendered accessibility tree now reads “origin to destination”. |
| P2 | At 320 px, the native date field was only about 115 px wide and clipped its visible date. | Date and time stack below 380 px; the measured date field is now about 239 px wide and the full date is visible. |

Files: `apps/web/app/globals.css`, `apps/web/components/trips.tsx`.
No new colors, typography, dependencies, product flows or persistence were added.

## Rendered verification

- Inspected the existing desktop layout and the 390 × 844 and 320 × 720 layouts.
- At 390 px, weekday controls measured about 74 × 44 CSS px; at 320 px, about
  56 × 44. The 320 px dialog's scroll width matched its client width (275 px),
  with vertical scrolling available for the longer form.
- Opened the new-plan dialog and selected Daily commute. Form labels, selected
  weekday state and the device-only/no-reminder disclosure remained available.
- Escape closed the dialog and restored focus to New plan. A keyboard step
  between the journey-type controls remained inside the dialog.
- Confirmed the corrected route names in the accessibility tree. Screenshots of
  desktop, narrow page and narrow dialogs were retained in this task's tool output.
- The unavailable-sign-in message correctly leaves saved local plans usable.
- No plans were saved, edited or removed during this audit. Viewport overrides
  were reset, the audit tab closed, and the temporary preview process stopped.
- Impeccable `detect` on Trips exited successfully without surfaced findings;
  that result is not evidence of full WCAG conformance or runtime performance.

## Unverified release checks

Real screen readers, browser text enlargement, reduced-motion preferences,
cross-browser native date widgets, Android touch behavior, authenticated journey
flows, storage failure and full contrast/performance measurement remain separate
release checks. Existing user data and the public LIVE publication gate were
unchanged.
