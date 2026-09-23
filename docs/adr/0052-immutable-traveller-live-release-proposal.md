# ADR 0052: Proposed immutable traveller LIVE release transcript

Date: 2026-09-23
Status: rejected as a release contract by independent privacy review; internal evaluation only

## Review disposition

The review found four release blockers. A Stop after the internal decision but before the fixed public release has no defined effect: preserving the snapshot allows a future release after Stop, while rechecking the source adds an observable participation-dependent decision. The eight-unknown-reports statement below is only a cohort count, not a person-level membership guarantee. Repeated windows, adjacent anchors and categories create an unbounded combined observation transcript; account read limits do not bound a coalition or previously delivered copies. Blocking, catalog changes, authority outages and emergency denial can still change availability and need one source-independent response contract. The current LIVE/privacy specifications require immediate suppression and have not been replaced. No public reader or activation flag may use this ADR.

## Scope and claim

This proposal replaces ADR 0051's contributor-dependent suppression rule. It does not claim individual anonymity or differential privacy. A deterministic aggregate can reveal group-level facts and can reveal a target's participation to an adversary with enough outside knowledge. The only quantitative claim under evaluation is that a released condition was supported by at least ten of at least twelve distinct, currently verified people at the decision point. Under the explicit assumption that an observer knows the exact reports of no more than two of those people and has no equivalent outside knowledge of the others, at least eight supporting reports are unknown to that observer. Human review limits duplicate-person accounts; it does not prove physical presence or independence of people.

The protected output excludes individual identity, exact GPS, route endpoints, counts, receipt times, raw reports, member lists and per-viewer subtraction. The observable transcript includes existence or absence, condition, canonical moment reference, category, coarse area label, fixed window label, response status, expiry, publication timing, cache behavior and changes across windows. The candidate 12/10 rule alone is not a privacy proof; the transcript and assumption require independent review against ADR 0038.

## Proposed release schedule and revocation

Windows are nonoverlapping five-minute intervals owned by the server and a versioned curated anchor catalog. Only evidence received before a window closes can qualify. Evaluation may occur after close and before the next five-minute boundary. A release is visible only at the fixed boundary ten minutes after the original window start and expires at the fixed boundary fifteen minutes after start. If a safe decision cannot be committed before release time, that window permanently yields no traveller moment. A late job cannot make a moment appear partway through the release interval.

An explicit Stop, Ghost Mode, journey completion, account deletion, verification revocation, consent withdrawal or moderator restriction committed before the decision point removes that source from eligibility. The publisher serializes its authority snapshot with those mutations and records a terminal decision. Once released, the canonical condition, moment reference, window label and expiry remain byte-for-byte stable for every authorized reader until fixed expiry. Later source changes prevent future releases but do not remove or edit the already released aggregate. This is a material change from ADR 0051's rejected immediate-suppression contract. The contributor disclosure must say that stopping may not remove an aggregate already released for up to five minutes. Already delivered offline copies cannot be recalled; clients mark them stale and clear them locally on Ghost Mode, account switch or journey end.

No source-dependent moderation action may silently edit or withdraw a released row. A category/region-wide emergency shutdown may deny all traveller moments through one independent operational gate, with a fixed generic response and audited reason, but cannot target one candidate or be triggered by a single report without further privacy review. No published count, freshness update, report total or mutable dispute marker is allowed.

## Reader and composition bounds

The server derives relevant anchors from the reader's owned active journey and current curated context. Clients cannot supply coordinates, anchor IDs or arbitrary geographic slices. Every authorized reader gets the same canonical row for an anchor/category/window; no block-specific evidence subtraction. An account with an active directed block edge receives no traveller-derived moments at all, regardless of whether the blocked person contributed. This all-or-none reader denial changes only that reader's access and never the canonical row.

The read response is no-store and DB-authoritative for the pilot; no realtime push or shared cache is permitted. A durable account rate gate limits reads. Window, anchor and category contribution budgets, repeated journey patterns, correlated reports and the total number of releases per person remain open for quantitative privacy and utility review. The full release must state a maximum observation horizon and evaluate cross-window composition; without that review, public activation remains prohibited.

## Required implementation evidence

- A durable multi-replica decision claim and lock order that serializes with Stop and every relevant authority mutation. No READ COMMITTED eligibility-query race may authorize publication.
- A terminal `NO_RELEASE` outcome for late, stale, sparse, conflicting, missing-authority or version-mismatched windows. Retries cannot change the canonical outcome or create a second moment reference.
- Fixed release/expiry timestamps and one response schema. No mutable metadata or per-user projection. A stable reportable moment reference is server-issued only for a released canonical row.
- Adversarial tests for known target Stop just before/after decision, Ghost Mode, completion, deletion, verification and moderation changes, block toggles, multiple replicas, account rotation, threshold-minus-one collusion, cross-window composition, stale clients, clock skew and missing dependencies.
- Independent privacy review of this proposal and the actual implementation transcript, plus a product disclosure review, before any public reader or flag is enabled.

## Relationship to other decisions

ADR 0038's impossibility result still applies to unrestricted collusion. ADR 0051 remains rejected. This proposal is an implementation target behind closed gates, not an approval to expose traveller output. It is not a differential-privacy mechanism; [NIST SP 800-226](https://csrc.nist.gov/pubs/sp/800/226/final) provides evaluation guidance if randomized mechanisms are later considered.
