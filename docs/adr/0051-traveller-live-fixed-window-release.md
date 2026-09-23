# ADR 0051: Candidate fixed-window traveller LIVE release

Date: 2026-09-23
Status: rejected for public release after independent adversarial review; private prerequisites may continue

## Decision under review

Build a separate, explicit public-share path from an accepted private Quick Signal. A private consent toggle or receipt alone never authorizes public use. A human-reviewed, revocable one-person authority is mandatory. The canonical output is one immutable coarse condition per curated anchor, category and nonoverlapping five-minute window; every authorized reader sees the same output. No per-viewer evidence subtraction, arbitrary area query, count, individual timestamp, person identifier or raw report is public.

The candidate release policy requires at least twelve distinct currently verified people and at least ten agreeing on the same structured condition, with at most one effective contribution per person/anchor/category/window. It assumes an adversary controls or knows the reports of at most two distinct verified people in a candidate and lacks independent knowledge of the remaining reports. This assumption is not established by human review, and the output does not provide anonymity against a coalition that knows all but one contribution. Sparse or conflicting evidence yields no traveller moment. No condition or location is fabricated to meet a threshold.

Publication is computed once after the window closes, using current journey, explicit public-share, consent, context/catalog, verification, restriction and evidence authority. Its state is durable and shared across replicas. A source withdrawal, Ghost Mode, journey completion, account deletion, verification revocation or moderator restriction permanently suppresses the entire published window. Suppression never recomputes from the remaining people. Public rows have a short fixed expiry; already delivered offline data cannot be recalled and must be marked stale or cleared locally as applicable.

Accounts with any active block edge cannot receive traveller-derived LIVE; this avoids a per-target subtraction or denial oracle. They still retain basic journey utility and provider alerts. Re-enabling access cannot revive a suppressed window. Readers must use an owned active journey and current curated route context, with database-backed account limits; no anchor or coordinate parameter exists.

## Review disposition

The independent review rejected public activation. A target's externally observable journey completion, Ghost Mode change or withdrawal can permanently suppress the whole window. An observer who sees the row disappear can infer the target's participation even without knowing any other reports. Limiting known reports to two does not limit knowledge of authority changes. The proposal also lacks a complete neighboring-data relation, permitted inference, numeric read and composition budgets, public expiry, and suppression schedule across anchors, categories and windows. The block rule can deny a target's entire feed while an unblocked observer continues watching. These are release-blocking findings; the 12/10 rule is not an approved privacy threshold.

The internal explicit-share ledger captures verification and moderation restriction revisions and holds a short pseudonymous person/window slot through account deletion. It does not publish, and later restoration must never revive an invalidated intent. These prerequisites do not cure the transcript-level disclosure.

An immutable five-minute release was also reviewed as an alternative: once published, the server would continue serving the exact same row until its original expiry despite later withdrawal, Ghost Mode, completion or deletion; those actions would prevent future windows only. This removes the specific post-publication disappearance oracle, but changes the accepted revocation semantics. It supports only a narrow cohort-floor statement under the two-known-person assumption, not an individual membership guarantee. Pre-close intervention, cross-window/category composition, full response and cache immutability, emergency moderation, block behavior, and user disclosure remain unresolved. Do not implement or enable that alternative until its product/privacy contract is explicitly approved.

## Why this is not a release approval

The bounded-coalition assumption, numeric utility, fixed-window read transcript, suppression timing and residual inference need independent review against ADR 0038's threshold-minus-one attack. Revocation necessarily changes availability and can itself disclose information. A human-reviewed identity reference does not prove physical presence or non-collusion. The reviewed claim must state this limit in product disclosures; no universal non-association or road-safety claim is permitted.

The operator verification workflow, reporting, moderation, block-safe delivery, independent review, real OAuth/catalog/provider staging, and rendered device QA must pass before any traveller-derived endpoint or flag exposes output. NIST's [Guidelines for Evaluating Differential Privacy Guarantees](https://csrc.nist.gov/pubs/sp/800/226/final) informs the evaluation of repeated releases and correlated data; this candidate is not a differential-privacy mechanism and makes no DP guarantee.
