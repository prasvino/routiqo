---
name: routiqo-ui-quality
description: Audit, critique, polish, or simplify Routiqo web, admin, and mobile interfaces while preserving its travel-first design system, accessibility, offline behavior, and privacy boundaries. Use for UI quality work, not backend-only changes.
---

# Routiqo UI quality

Use the installed project-local Impeccable skill selectively. Prefer `audit`,
`critique`, `polish`, and `distill`; do not run all four automatically or redesign
an existing screen merely to satisfy a detector. This skill adds Routiqo-specific
checks, not a second design system.

## Context and routing

Start with repository `AGENTS.md`, `docs/PRODUCT.md` (product source of truth)
and `docs/design/ROUTIQO_UI_UX_SYSTEM.md`. Read the relevant feature spec and
current tokens/components only as needed. Treat the incumbent app as an existing
product.

Routiqo's PRODUCT.md is `docs/PRODUCT.md`. Impeccable discovers PRODUCT.md upward
from the app to the git root, so it will not find it automatically; read it
directly as product context and do not run Impeccable `init` to write a competing
root PRODUCT.md. There is no DESIGN.md; tokens and the UI/UX system are the visual
record. Neither situation authorizes rebranding, replacing fonts, or inventing
product behavior.

Read `../impeccable/SKILL.md` and the selected playbook, relative to this folder:

| Intent | Playbook | Result |
|---|---|---|
| Find technical defects | `../impeccable/reference/audit.md` (or `audit.native.md`) | Evidence and prioritized findings; no automatic fixes |
| Evaluate hierarchy and usability | `../impeccable/reference/critique.md` | Targeted design assessment; distinguish visual judgment from detector facts |
| Refine an approved interface | `../impeccable/reference/polish.md` | Fix spacing, typography, states and interaction defects while preserving identity |
| Reduce cognitive load | `../impeccable/reference/distill.md` | Remove duplication and visual noise without deleting required controls or disclosures |

Use the Windows launcher `.agents/skills/impeccable/scripts/impeccable.cmd`
from the repository root for engine operations. The four modes are skill
playbooks, not assumed engine CLI verbs. Load context once for a design session;
inspect before editing. Follow repository orchestration and the chosen playbook's
review requirements when running an actual critique. This setup does not itself
authorize an unsolicited whole-app critique or implementation pass.

## Product checks that determine the outcome

- Follow `docs/PRODUCT.md` principles. The active Journey map is the hero: route,
  "me" and Spots ahead by distance; three ideas only (Journey, Spots, Ask Ahead).
  Show places and posts, not people: no traveller clusters, avatars, presence or
  traveller counts; report counts ("3 reports in 20 min") are allowed. Fresh or
  gone: show capture time/freshness, never expired content as current; highlights
  only after expiry. Honest empty states ("No recent posts", "No answers yet")
  with latest signals; never fake activity. Per-room aliases; no profiles,
  follower cues or DMs; Report and Block on every item. One tap first; voice notes
  before long text. Driver safety: the post-passing prompt is a quiet dismissible
  card only when stopped/slow or a passenger, never a modal or sound while moving;
  no engagement-bait prompts. Ghost Mode stops all sending, including Spot passage
  and queued posts, and stays visible and immediate.
- Commutes and trips keep different workflows; rich journals are not commute
  summaries. Chat belongs to a Spot or room, not a permanent social tab. Tabs
  are Home, Explore (renamed Guides when route guides ship), Trips and Profile;
  the active Journey is a full-screen map mode from Home with a "Back to journey"
  bar, not a tab. Do not change the tab structure during polish.
- Active journeys prioritize glanceable state, one-handed controls, readable
  hierarchy and minimal typing. Discovery may be more expressive. Neither needs
  decorative cards, redundant badges or motion that competes with the task.
- Reuse shared tokens and semantic colors. Diagnose whether drift comes from a
  missing token, duplicated component, inconsistent flow, or a local defect.
  Do not add themes, fonts or dependencies solely to improve a generic score.
- Check keyboard/focus order, visible focus, labels and errors, contrast in actual
  supported states, touch targets, narrow layouts, zoom/large text, text wrapping,
  reduced motion and layout stability. Use platform guidance for native work.
- Exercise loading, empty, error, retry, offline, permission-denied and reconnect
  states relevant to the change. Simplification must preserve unsaved drafts,
  conflict recovery, exact retries and account-switch clearing.
- Spot UI must not imply verified presence, safe roads, crowd counts or fresh
  data without evidence. Preserve stale/expired states, Ghost Mode and the
  Spot-passage opt-in, source attribution and privacy disclosures. UI polish
  cannot enable a default-off feature or present synthetic signals as real
  observations. Archived LIVE/consent screens stay default-off.
- Keep map/GPS updates isolated. Measure before adding memoization or performance
  complexity; avoid unnecessary map reloads, image layout shifts and unbounded
  animations. A clean detector result is not performance or accessibility proof.

## Verification and reporting

Inspect the actual rendered target at representative desktop and narrow web sizes,
or the available native environment. Batch observations, fix confirmed issues,
then confirm the affected paths. Android is the primary pilot client: phase gates
require physical Android device evidence (small screen, large text, mid-range
device, weak network). Cloud sessions have no Android SDK or emulator; record
those checks as pending. Record inaccessible/device-dependent checks as
unverified; screenshots and automated scores do not prove an end-to-end journey.
Choose relevant component tests, lint/types and build checks for the change. Do
not repeat unrelated backend suites for visual-only edits.

Report findings with severity, file/route, evidence, user impact and the smallest
useful remedy. Separate confirmed defects, design judgment, detector false positives
and unverified checks. When implementing, report what changed and how it was
verified. Preserve authorized scope and use known project context for routine
choices rather than repeatedly asking the user.

Hooks are advisory tooling, not product or security authority. Inspect the current
`.codex/hooks.json` and referenced executable/launcher before trusting a changed
hook through Codex's `/hooks` review. Do not bypass hook trust or silently suppress
real findings. Keep installation and settings project-local.
