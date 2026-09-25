---
name: routiqo-ui-quality
description: Audit, critique, polish or simplify Routiqo web, admin and mobile interfaces while preserving the travel-first design system, accessibility, offline behavior and privacy boundaries. Use for UI quality work, not backend-only changes.
---

# Routiqo UI quality

This skill adds Routiqo-specific checks to UI work. It is not a second design system. Treat the current app as an existing product. Do not rebrand it, replace its fonts, add themes or invent product behavior to improve a generic score. Only do a whole-app critique or redesign when the user asks for one.

## Context

1. Read `AGENTS.md` / `CLAUDE.md`, `docs/PRODUCT.md` (the product source of truth) and `docs/design/ROUTIQO_UI_UX_SYSTEM.md`. The UI/UX document addresses Codex, but its rules apply to Claude unchanged.
2. Read the relevant feature spec and the current tokens (`packages/design-tokens/src`) and components only as needed.
3. Routiqo's product record is `docs/PRODUCT.md`. Impeccable discovers `PRODUCT.md` upward from the app to the git root, so it will not find `docs/PRODUCT.md` on its own. Read that file directly and treat it as the product context. Do not run Impeccable `init` to write a competing root `PRODUCT.md`. There is no `DESIGN.md`; the tokens and the UI/UX system are the visual record.
4. If Impeccable is installed for Claude under `.claude/skills/impeccable/` (`npx --yes impeccable install -y --providers=claude --scope=project --no-hooks`; the existing `.agents/` copy is the Codex install), you may use its `audit`, `critique`, `polish` or `distill` playbook. Choose the one that matches the request; do not run all four. Without it, apply the modes below directly.

| Intent | Result |
|---|---|
| Audit: find technical defects | Prioritized findings with evidence; no automatic fixes |
| Critique: evaluate hierarchy and usability | Targeted assessment that separates visual judgment from measured facts |
| Polish: refine an approved interface | Fix spacing, typography, states and interaction defects while keeping the existing identity |
| Distill: reduce cognitive load | Remove duplication and noise without deleting required controls or disclosures |

## Product checks

- Follow the `docs/PRODUCT.md` principles:
  - The active Journey map is the hero, with the route, "me" and the Spots ahead ordered by distance. Users learn three ideas: Journey, Spots and Ask Ahead.
  - Show places and posts, not people. There are no traveller clusters, avatars, presence or traveller counts. Report counts such as "3 reports in 20 min" are allowed.
  - Fresh or gone. Show capture time and freshness, and never show expired content as current. After expiry, highlights only.
  - Honest empty states. "No recent posts" or "No answers yet" comes with the latest signals. Never fake activity or show synthetic crowds.
  - Per-room aliases, with no profiles, follower cues or DMs. Every item has Report and Block.
  - One tap first. Voice notes come before long text.
  - Driver safety. The post-passing prompt is a quiet, dismissible card shown only when stopped, slow or a passenger. It is never a modal or sound while moving, and it never uses engagement-bait prompts.
  - Ghost Mode stops all sending, including Spot passage and queued posts. Keep it visible and immediate.
  - Commutes and trips keep different workflows, and rich journals are not commute summaries. Chat belongs to a Spot or room, not to a permanent social tab. Treat Explore as demoted toward route guides. The final tab structure is a design decision, so do not change it during polish.
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
- Spot UI must not imply verified presence, safe roads, crowd counts or fresh data without evidence. Keep stale and expired states, Ghost Mode and the Spot-passage opt-in, source attribution and privacy disclosures. UI polish cannot turn on a default-off feature or present synthetic signals as real observations. Archived LIVE and consent screens stay default-off.
- Keep map and GPS updates isolated. Measure before adding memoization or performance complexity. Avoid unnecessary map reloads, image layout shifts and unbounded animations.

## Verification and reporting

- Web: run the app (`pnpm dev:web` or `pnpm dev:admin`) and inspect it with Playwright/Chromium at desktop and narrow (~360 px) widths. Batch observations, fix confirmed issues, then re-check the affected paths once.
- Native: Android is the primary pilot client. Phase gates need evidence from a physical Android device, including small screens, large text, a mid-range device and a weak network. The cloud container has no Android SDK or emulator, so record device checks there as pending instead of claiming them.
- Run the relevant component tests, `pnpm typecheck`, `pnpm lint` and the build for the changed app. Do not rerun unrelated backend suites for visual-only edits.
- Save screenshots under `docs/quality/evidence/<topic>-<date>/`, labelled synthetic where they are.
- Report findings with severity, file or route, evidence, user impact and the smallest useful fix. Keep confirmed defects, design judgment, detector false positives and unverified checks separate. A screenshot or a clean automated score is not proof of accessibility, performance or an end-to-end journey.
