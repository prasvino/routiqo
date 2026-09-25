> **Archived 2026-09-25.** Threshold, cohort, differential-privacy or V3 community-summary material. The pilot publishes no aggregate traveller output; revisit only if aggregate counts return after a separate privacy review. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# Public LIVE protocol implementation plan

Status: in progress. The user authorized pursuing the final, explicitly disclosed Share design on 2026-09-23. This is not approval to enable traveller publication. ADR 0054 and its adversarial review define the current candidate and open findings.

## Goal and acceptance

Close item 1 in `todo.md` only when the complete person-level public LIVE protocol, including data collection and the public observable transcript, receives independent approval. An isolated DP sampler or a private Share API is insufficient. No public endpoint, reader, flag or user-facing irreversible Share action may be activated before that approval.

1. A successful versioned Share commits one immutable canonical key and a nonrefundable one-person pilot claim atomically, under current journey, consent, context, verification and safety authority. Exact retries recover the same outcome; other attempts cannot change it. Legacy V18 ACTIVE/STOPPED intents never become public input.
2. Natural-person identity assignment remains stable and auditable across deletion, account re-entry, correction and reviewer error for the pilot and approved retention. No claim of one-person sensitivity without that operated boundary.
3. A specified and tested Share commit/window seal rule ensures one person's actions cannot change another person's admitted key or a fixed publication deadline. A data-dependent `NO_RELEASE` is a privacy failure, not a safe fallback.
4. Fixed manifest and one-time sparse randomized projection produce at most one canonical condition per anchor/window. Every visible status, timing, reference, error, block, report, cache and offline behavior is included in the reviewed transcript. Source actions after Share cannot edit released bytes.
5. Product/privacy/legal review approves the exact [V2 browser disclosure](../features/live/BROWSER_PUBLIC_SHARE_V2_SPEC.md), Stop/Ghost/deletion wording and bounded retained person claim. Real consented density and accuracy establish useful output at threshold 22, including one-slot depletion.
6. Independent privacy/security review of the final code, tests and staging transcript records a positive disposition. Until then, traveller publication and V2 Share remain disabled.

## Phases and affected boundaries

1. **Private frozen-input foundation:** add versioned storage and an internal service separate from V18; reuse account-before-journey authority and V21 person claim in one transaction. Add PostgreSQL rollback, replay, duplicate-person, deletion and race tests. Do not wire consumer HTTP or activate a pilot.
2. **Identity authority:** design the stable person-reference registry and independently authenticated operator workflow, with retention and correction policy. Do not improvise raw identity storage in route/signal tables.
3. **Seal and publisher design:** resolve commit-order versus window close, cross-person lock/resource contention, finite worst-case workload and source-independent failure. An independent feasibility review found a counterexample to the current barrier/deadline approach; a PostgreSQL logical snapshot is suitable for a private consistency experiment but cannot by itself prove the fixed public transcript. Review a complete alternative before writing a public publisher. If no defensible rule exists, keep traveller output closed and use the official-alert pilot for V1.
4. **Public delivery:** only after protocol approval, add the canonical publisher, reader, report target, typed clients and active-journey list. Test authorization, revocation, repeated reads, offline state, caches and all transcript metadata.
5. **Release validation:** real OAuth and region/catalog configuration, authenticated staging, load and failure injection, utility measurement, device/accessibility QA, operational retention and independent adversarial review. Release remains a separate decision.

## Rollback and safety

All new capabilities default off. A rollback must never reenable a consumed person slot or regenerate a published result. Private V18 behavior remains unchanged until a separately versioned and disclosed V2 transition is ready. No existing ACTIVE intent is migrated to public input. No current user report becomes public through a migration.
