# Routiqo Live: first release plan

Status: planned, not implemented. Agreed planning scope: an active-journey LIVE
list with Live Moments and Quick Signals. This specification reconciles the
user-supplied ROUTIQO_LIVE_PRODUCT_PLAN.md; examples in that input are illustrative,
not evidence of activity or authorization to enable services. No coding is part
of this documentation change.

## Product and ownership

Routiqo Live helps travellers understand recent situations relevant to their
journey and contribute useful observations. Preserve Home, Explore, Trips and
Profile. LIVE belongs inside the active journey, with a list first; it is not a
fifth tab, nearby-person browser or generic feed. Basic journey utility remains
available without participation.

| Concept | Ownership and reuse |
|---|---|
| Live Moment | Temporary situation derived from eligible Route Update evidence at a server-defined route segment or place |
| Quick Signal | Structured Route Update input, not a second competing report store |
| Same Situation | Internal eligibility/cohort policy in the presence boundary; no participant list or public cohort directory |
| Route Room | Later optional conversation attached to a relevant moment/journey; no operational rooms exist yet |
| Living Route | Active-journey list now; map representation of the same authorized projection later |
| Pulse | Later AI adapter consumes approved evidence projections, never raw presence or private conversations by default |

Presence consent/lease foundations, journey ownership, authentication, account
isolation, contracts and abuse controls must be reused through application
interfaces. Existing pure policies and realtime scaffolds do not constitute
working public presence or conversation services.

## First-release experience

An authenticated active journey can show up to 20 relevant situations, ordered by
route relevance and freshness. Prefer a known public stop or coarse corridor label;
never expose precise traveller coordinates or private endpoints. A row shows:

- a short situation label and structured condition;
- coarse freshness and explicit expiry/staleness;
- source type: traveller-reported or separately identified provider evidence;
- corroborated, conflicting or insufficient evidence, using reviewed rules;
- an accessible action to contribute a context-appropriate Quick Signal.

Do not show traveller totals, respondent totals, report counts, contributor IDs,
avatars, member lists, individual timestamps, exact lane recommendations or exact
delay estimates in this release. Queue durations are reported bands, not measured
ETAs. No recent reports does not mean no incident or that a route is safe.

Initial signal categories: queue (`under-5`, `5-to-15`, `15-to-30`, `over-30`
minutes), traffic (`moving`, `slow`, `very-slow`, `stopped`), parking (`available`,
`filling`, `full`), food queue (`none`, `short`, `long`), restroom
(`usable`, `busy`, `problem-reported`). Eligibility and category availability are
server-controlled for each anchor. No free text, photos, voice, bus platform
changes or emergency dispatch in this first slice. Operational transport updates
need a reliable service identity and a separate evidence/safety design.

The empty state says there is not enough recent information. Do not invent moments,
synthetic crowds, AI content or confidence to populate the live interface. Label
test fixtures clearly and keep them out of production responses.

Submitting remains deliberate, with a visible receipt or failure; do not imply
that one submission immediately appears publicly. Contributions are optional,
infrequent and dismissible. Do not solicit interaction during driving. Native
automatic prompts/background sensing are out of scope. The web flow must not
claim it can reliably determine whether the user is driving; use an explicit
stopped/passenger context and suppress proactive prompts.

## Eligibility and privacy release gates

Ownership of a journey or possession of a route ID is not evidence that a person
is physically present. The server must bind an active owned journey to a validated
route context, fixed corridor partitions/public anchors, and short-lived admission.
Client GPS remains untrusted. Claims remain traveller reports, not verified
observations of physical presence. Arbitrary bounds, segments and membership
probing are denied; no account-independent live discovery endpoint is planned.

Follow PRESENCE_SPEC.md for consent, generation, expiry and anti-enumeration.
Reading does not implicitly opt a traveller into publishing. Ghost Mode prevents
new signals/publishing, revokes pending eligibility and makes already accepted
contributions ineligible for future projections through the reviewed suppression
policy. Reading under Ghost Mode
may be allowed only using owned-journey relevance, without a presence lease or
observable reader membership. Ending a journey, account changes and deletion
revoke admission and clear private client state.

Do not publish a moment from an identifiable or single reporter. The existing
proposed minimum of 10 distinct eligible actors is a starting privacy-review
candidate, not a guarantee or release approval. Before public output, specify and
test fixed publication windows, minimum independent evidence, suppression,
resistance to account collusion, overlapping queries and temporal differencing.
Freshness badges and moment appearance/disappearance are observable signals too.

Blocking, moderation, consent withdrawal and deletion must remove prohibited
contributions from future outputs and invalidate affected caches. Do not create
viewer-specific count differences or stable contributor handles. Choose the exact
block-safe aggregation/suppression policy in the cohort ADR before aggregate API
implementation; do not silently subtract a blocked reporter and publish a
distinguishable replacement. Fail closed when policy authority is unavailable.

## Evidence lifecycle and writes

Implement the pure lifecycle/validation model first. Initial design values below
are conservative proposed defaults, to be finalized with the cohort/storage ADRs
before migrations or public endpoints:

| Record | Proposed lifecycle |
|---|---|
| Quick Signal | Server-received timestamp; useful for 15 minutes; one current contribution per actor/anchor/category; replacement cannot add independent corroboration |
| Live Moment | Derived only from eligible, nonexpired evidence; disappears when publication rules cease to hold; no permanent browsable archive |
| Submission receipt | Account-bound idempotency key and request fingerprint, retained 24 hours; a retry after evidence expiry cannot resurrect evidence |
| Raw signal | Purge by 24 hours unless a specific bounded moderation hold applies; never store a raw GPS trail |
| Moderation hold | Separate access-controlled minimal evidence; duration, deletion exceptions and audit policy must be agreed before retention is enabled |
| Client LIVE state | Memory only; no planning backup, journal, persistent offline cache or analytics payload containing live evidence |

Use server time for expiry. Client clocks and retries cannot refresh an observation.
Reject contributions received outside their admissible observation window. A
proposed starting quota is one accepted contribution per actor/anchor/category
per minute and 20 across the actor per hour; distributed duplicate, peer and abuse
budgets also need definition. No numeric value here bypasses adversarial review.

Moment formation should be deterministic from approved evidence rules, not AI.
Expose conflicts without fabricating a precise consensus. Distinct accounts alone
do not prove independent witnesses. Confidence labels require explicit tested
corroboration rules; omit a label when not justified.

## API, storage and delivery

See ADR 0022 for the architectural direction. OpenAPI schemas must precede public
HTTP implementation: bounded list, structured submission/receipt and report actions
with owned-journey context, opaque short-lived references, fixed enums and generic
denials. Do not freeze endpoint names in this plan; coordinate them with existing
journey contracts. Never return raw membership, internal account keys or GPS.

Start with bounded authenticated HTTP refresh, foreground only, no more often than
once per minute, with one in-flight request and cancellation/backoff. Manual refresh
uses the same server budget. Do not rebuild the realtime gateway for the first
list. Reads require current authorization on every request; expiry applies between
refreshes. Reconnect reauthorizes and refreshes, never replays a contribution.

Offline: retain visible rows only in memory, explicitly label them stale, remove
expired rows and disable submission/refresh. Existing durable start/finish outboxes
continue unchanged. Disconnected clients cannot receive remote revocation and must
not claim immediate erasure of previously delivered information. Local Ghost,
account and journey-end transitions clear the LIVE projection immediately; remote
changes take effect at expiry or the next successful authorization check. Server
failures never authorize new output. Live reports must not enter those queues or
be uploaded later as fresh observations. Reconnection alone must not resubmit an uncertain write;
retry the exact idempotent request explicitly within its validity window.

Support loading, cold start, sparse/suppressed evidence, offline, stale, expired,
permission denied, revoked consent, throttled, uncertain submission and service
unavailable states. Use coarse non-disclosing errors for unauthorized, hidden and
missing resources. Avoid per-second live regions or announcement floods.

## Implementation sequence and acceptance

1. **L0: domain and decisions.** Define lifecycle, signal enums, consent transitions,
   temporal/idempotency rules and evidence projection interfaces as pure policies.
   Resolve cohort publication, admission and retention ADRs before exposed data.
2. **L1: protected service foundations.** Durable consent/admission, distributed
   quotas, bounded persistence/cleanup, idempotency, blocking/reporting and minimum
   operator moderation. Implement signal ingestion without public moment output
   until privacy gates pass. No authentication bypass for demonstrations.
3. **L2: first useful release.** Aggregate projection and authenticated LIVE list
   plus structured contribution, on a small reviewed corridor pilot. Ship only
   after authorization, anti-correlation, lifecycle, moderation, offline and
   accessibility acceptance passes. This is the target first release.
4. **L3: shared map projection.** Same authorized moments on list/map; no new
   fine-grained query or precise-person layer.
5. **L4: Ask Ahead.** Separate design for recipient consent, bounded random selection,
   no repeat targeting, expiry, blocks and response aggregation. No recipient list.
6. **L5+:** Temporary Route Rooms; evidence-backed Pulse; Travel Waves after useful
   participation is demonstrated. Mutual connections, DMs and social graphs are
   not first-release requirements.

Regional maps and real sign-in verification remain prerequisites for the pilot;
full on-device navigation, downloaded regions and AI are not prerequisites for
the first LIVE list. Existing journey/offline reliability work continues.

Success: time to useful fresh information, proportion of eligible journeys with
useful moments, useful signal contribution and stale/false/abusive report rates.
Define privacy-preserving aggregate measurement before collecting telemetry;
do not optimize time spent, message volume or notification count.

Required verification is owned by TESTING_STRATEGY.md; threats by THREAT_MODEL.md;
secure engineering by SECURITY.md; review process by CODE_REVIEW.md. BUILD_STATUS.md
continues to record only implemented and verified capabilities.
