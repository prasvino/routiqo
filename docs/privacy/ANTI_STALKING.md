# Anti-stalking
No public participant lists, cross-route person search, exact stranger positions or public movement histories.
Future presence needs threshold suppression, limited query budgets and anti-correlation review across overlapping segments/time windows. Blocking affects all channels. Ghost Mode removes discoverable state across caches/replicas.
Threat tests must cover repeated queries, sparse corridors, membership scraping and block circumvention before route rooms launch.

## Current internal safety boundary

ADR 0038 keeps public publication closed: threshold-minus-one colluding accounts
can infer a target's contribution from appearance, disappearance or block toggles.
Shared output and larger thresholds alone do not resolve that attack. Google
account uniqueness and private write budgets are not independent-human evidence.

ADR 0039 enforces contribution suspension and revision fencing on new private
signal writes. Retained owner receipt recovery and withdrawal remain available;
these do not authorize new publication. ADR 0040's private block store is implemented
and independently reviewed. A bilateral clear decision is valid only inside its
current transaction; it is not a durable publication capability. Future delivery
must compose current block/consent/restriction checks and revocation, including
reconnects, without viewer-specific subtraction or an account directory.

Reporting authority and investigation lifecycle remain unresolved; see
`docs/features/live/DURABLE_REPORT_INTAKE_PROPOSAL.md`. Opaque UUIDs alone never
authorize a report about another actor or reveal that actor's participation.
