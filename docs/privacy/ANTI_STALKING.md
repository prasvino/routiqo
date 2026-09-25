# Anti-stalking
No public participant lists, cross-route person search, exact stranger positions or public movement histories.
The pilot collects no passive presence (see [`docs/PRODUCT.md`](../PRODUCT.md)). If aggregate traveller counts return, they need threshold suppression, limited query budgets and anti-correlation review across overlapping segments/time windows, under a separate privacy review. Blocking affects all channels. Ghost Mode stops all sending and removes discoverable state across caches/replicas.
Threat tests must cover repeated queries, sparse corridors, membership scraping and block circumvention before Spot chat or the festival route room launch.

## Pilot identity and contact rules

Planned for the pilot; not implemented unless
[`BUILD_STATUS.md`](../quality/BUILD_STATUS.md) says so.

- **Per-room aliases.** A random alias per room (e.g. "Blue Auto"). Aliases are
  not stable public handles, never embed account identifiers and cannot be
  linked across rooms, Spots or events by other users. Alias generation and
  collision handling must not leak account identity or join order.
- **No private DMs** between strangers in the pilot. No follower graph.
- **Spot passage is never visible to others.** No surface shows who passed a
  Spot, when, or how many opted-in passers exist. Report and reply counts are
  allowed; traveller counts are not.
- **Ask Ahead recipient anonymity.** The asker never learns who was offered a
  question, how many people were offered it, or whether a given person was
  eligible. Recipients come only from opted-in recent passers of that Spot.
  Selection is bounded and non-deterministic, respects blocks in both
  directions and does not repeatedly target the same person. Answers appear
  under the answerer's per-room alias only. Unanswered questions say so
  honestly; silence reveals nothing about who was offered.
- **Spot chat and festival room.** Membership is not listable. Bound history
  reads and subscriptions so repeated joins, alias harvesting or scrolling cannot
  scrape participants. Blocks apply to room subscription, delivery, replay and
  Ask Ahead selection; a blocked user cannot regain visibility by rejoining,
  switching room or using a new alias in the same account. New accounts get
  stricter limits to slow block circumvention through fresh accounts.
- Every post, voice note and chat item has Report and Block. Moderators can hide
  content quickly.

## Current internal safety boundary

ADR 0039 enforces contribution suspension and revision fencing on new private
signal writes. Retained owner receipt recovery and withdrawal remain available;
these do not authorize new publication. ADR 0040's private block store is implemented
and independently reviewed. A bilateral clear decision is valid only inside its
current transaction; it is not a durable publication capability. Future delivery
must compose current block/consent/restriction checks and revocation, including
reconnects, without viewer-specific subtraction or an account directory.

Reporting authority and investigation lifecycle remain unresolved; see
[`DURABLE_REPORT_INTAKE_PROPOSAL.md`](../features/live/DURABLE_REPORT_INTAKE_PROPOSAL.md).
Opaque UUIDs alone never authorize a report about another actor or reveal that
actor's participation.

## Archived: collusion against published aggregates

ADR [0038](../adr/0038-publication-threat-boundary-and-safety-prerequisites.md)
keeps public publication closed because threshold-minus-one colluding accounts
can infer a hidden contributor from a summary's appearance, disappearance or
block toggles; Google account uniqueness and private write budgets are not
independent-human evidence. The pilot has no hidden contributors behind an
aggregate, so this analysis is archived under
[`docs/archive/`](../archive/README.md). It applies again if aggregate counts
return.
