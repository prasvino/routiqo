# Planning accessibility pass — rendered QA (2026-09-25)

Production web build driven by Playwright/Chromium. `/api/v1` was a **synthetic** signed-out stub; planning is
local-only, so no real sign-in, core API or account planning copy was involved. Storage failures were simulated by
replacing `Storage.prototype` methods in the page.

## Confirmed defects fixed

| Severity | Where | Defect | Fix |
|---|---|---|---|
| Medium | Trips → Remove plan | After confirming a removal, focus fell to `<body>` because the dialog tried to restore it to the removed plan's button | Focus moves to **New plan** after a successful removal; Keep plan still returns focus to the trigger |
| Low | Plan dialog | The Trip/Commute toggle's `aria-label` sat on a plain `div`, so assistive tech did not announce the "Journey type" group | `role="group"` |
| Low | Planning storage errors | Blocked or full storage showed the browser's raw exception text ("Denied", "Quota exceeded") | Actionable copy for storage exceptions; planning-rule messages (limits, corrupt data) unchanged |

## Checked with no defect

- Keyboard: page tab order; dialog opens on Close, tabs through type, fields, days and Save; Escape closes and returns focus to New plan; an empty commute focuses the first day with an inline alert.
- Reflow: no horizontal overflow at 320 px (`320-zoom1-days.png`), at 360 px with 130% zoom (`narrow-1.3-*`) or at 360 px with 200% text-only scaling (`360-text200-days.png`).
- Reduced motion: no running animations with the dialog open under `prefers-reduced-motion: reduce`.
- Storage failure: the dialog stays open, the entered note is kept, and the error is announced (`storage-*.png`).

## Not defects / not verified

- "No visible focus" flags after mouse clicks are expected `:focus-visible` behaviour.
- Overflow in `narrow-2-*` and `360-zoom2-days.png` comes from CSS `zoom: 2` on a 360 px viewport (180 CSS px, below the WCAG 320 px reflow width); CSS zoom does not trigger the layout's media queries the way browser zoom does.
- Design judgment, not changed: several labels and notes use 10–12 px text at default size.
- Not verified: screen readers, real browser zoom, native Android, signed-in planning copy.
