---
name: routiqo-ui-quality
description: Audit, critique, polish or simplify Routiqo web, admin and mobile interfaces while preserving the travel-first design system, accessibility, offline behavior and privacy boundaries. Use for UI quality work, not backend-only changes.
---

# Routiqo UI quality

This skill adds Routiqo-specific checks to UI work. It is not a second design system. Treat the current app as an existing product. Do not rebrand it, replace its fonts, add themes or invent product behavior to improve a generic score. Only do a whole-app critique or redesign when the user asks for one.

## Context

1. Read `CLAUDE.md` and `docs/design/ROUTIQO_UI_UX_SYSTEM.md`.
2. Read the relevant feature spec and the current tokens (`packages/design-tokens/src`) and components only as needed.
3. If a project-scoped Impeccable skill is installed under `.claude/skills/impeccable/`, you may use its `audit`, `critique`, `polish` or `distill` playbook. Choose the one that matches the request; do not run all four. Without it, apply the modes below directly.

| Intent | Result |
|---|---|
| Audit: find technical defects | Prioritized findings with evidence; no automatic fixes |
| Critique: evaluate hierarchy and usability | Targeted assessment that separates visual judgment from measured facts |
| Polish: refine an approved interface | Fix spacing, typography, states and interaction defects while keeping the existing identity |
| Distill: reduce cognitive load | Remove duplication and noise without deleting required controls or disclosures |

## Product checks

- Keep Home, Explore, Trips and Profile. Commutes and trips have different workflows, and rich journals are not commute summaries. Conversation belongs to an active journey, not a permanent social tab.
- Active journeys need glanceable state, one-handed controls, readable hierarchy and minimal typing. Discovery can be more expressive. Neither needs decorative cards, redundant badges or motion that competes with the task.
- Reuse shared tokens and semantic colors. When the UI drifts from the design system, work out whether the cause is a missing token, a duplicated component, an inconsistent flow or a local defect.
- Check:
  - keyboard and focus order, and visible focus;
  - labels and errors;
  - contrast in real states;
  - touch targets;
  - narrow layouts, zoom and large text, text wrapping;
  - reduced motion and layout stability.
  For native work, follow the platform guidance.
- Exercise the loading, empty, error, retry, offline, permission-denied and reconnect states that the change affects. Simplifying must keep unsaved drafts, conflict recovery, exact retries and account-switch clearing.
- LIVE must not imply verified presence, safe roads, exact crowd counts or fresh data without evidence. Keep stale and suppressed states, Ghost and consent controls, source attribution and privacy disclosures. UI polish cannot open a closed publication gate or use synthetic signals as real observations.
- Keep map and GPS updates isolated. Measure before adding memoization or performance complexity. Avoid unnecessary map reloads, image layout shifts and unbounded animations.

## Verification and reporting

- Web: run the app (`pnpm dev:web` or `pnpm dev:admin`) and inspect it with Playwright/Chromium at desktop and narrow (~360 px) widths. Batch observations, fix confirmed issues, then re-check the affected paths once.
- Native: the Android emulator is not available in the cloud container. Record device checks as unverified instead of claiming them.
- Run the relevant component tests, `pnpm typecheck`, `pnpm lint` and the build for the changed app. Do not rerun unrelated backend suites for visual-only edits.
- Save screenshots under `docs/quality/evidence/<topic>-<date>/`, labelled synthetic where they are.
- Report findings with severity, file or route, evidence, user impact and the smallest useful fix. Keep confirmed defects, design judgment, detector false positives and unverified checks separate. A screenshot or a clean automated score is not proof of accessibility, performance or an end-to-end journey.
