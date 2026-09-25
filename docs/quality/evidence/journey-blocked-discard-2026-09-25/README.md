# Blocked journey action discard — rendered QA (2026-09-25)

Production web build (`pnpm --filter @routiqo/web build` + `start`) driven by Playwright/Chromium with reduced motion.
All `/api/v1` traffic was a **synthetic** stub: a signed-in session, `POST /journeys` → 409 (start blocked as conflict),
`GET /journeys/{id}` → 404 and an empty journey list. No real Google sign-in, core API or second device was used.

Flow on Trips: Start a journey → blocked → Check server status → "Discard unsent start" → confirmation → Discard →
"The unsent action was discarded. Recent journeys were refreshed from the server." and Start a journey is available again.

| Viewport                                   | Result                                                |
| ------------------------------------------ | ----------------------------------------------------- |
| 1280×900 (`desktop-*.png`)                 | flow complete, no horizontal overflow, no page errors |
| 360×780 at 130% text zoom (`narrow-*.png`) | flow complete, no horizontal overflow, no page errors |

In `narrow-3-confirm.png` the fixed bottom navigation crosses the element screenshot; the page scrolls normally.

Pending: native Android controls, real two-device sign-in, screen-reader pass.
