# ADR 0065: Public LIVE policy for V1

Date: 2026-09-25
Status: **accepted by the owner, 2026-09-25**

> **Status update (2026-09-25):** Partly superseded by the direction brief adopted later the same day. Still holds: no traveller-derived aggregate public output in the pilot; empty is better than wrong, privacy-invasive or manipulable information; no UI, document or code may claim anonymity; Sybil resistance must not collect more identity data (no government ID or Aadhaar, device fingerprints or location history); community flags fail closed on anything but exact `true`. Superseded: ADR 0055 continuing as a staging experiment (now archived) and private-only Quick Signals in V1 (one-tap signals become public on Spots under per-room aliases). See [PRODUCT.md](../PRODUCT.md).

## Context

[PUBLIC_LIVE_PRIVACY_DECISION.md](../archive/features/live/PUBLIC_LIVE_PRIVACY_DECISION.md) compared three options for traveller-derived public LIVE:
- person-level differential privacy (ADR 0054);
- the consented community traffic summary (ADR 0055, "V3");
- no traveller-derived public output.

The owner reviewed it and set the direction below. Routiqo must preserve user privacy, explicit and understandable consent, trustworthy LIVE information, strong abuse resistance, a fast simple experience and minimal identity/location collection, and must keep those as it scales. Coverage must never be bought by weakening them. **Empty LIVE information is acceptable; incorrect, privacy-invasive or manipulable LIVE information is not.**

## Decision

| Track | Status | Meaning |
|---|---|---|
| **Production V1** | Option C | Official/provider-derived LIVE information and private Quick Signals only. Traveller-derived community summaries must not be publicly activated in V1. |
| **Candidate future architecture** | ADR 0055, experimental | Continues only as a disabled-by-default, closed, explicitly consented staging experiment. Completing its implementation does not authorize production. A separate explicit decision is needed after the evidence below. |
| **Research** | ADR 0054, paused | Research, documentation and disconnected code are kept. No further implementation effort unless a future decision reopens it. |

Option C is the V1 production configuration. It is **not** Routiqo's permanent answer for community LIVE.

### ADR 0055 pilot model (unchanged)

The pilot keeps the existing model:
- an explicit Share of an eligible Quick Signal;
- one account contribution per aggregation window, and daily limits;
- the conservative 12 accounts / 10 agreeing / at least 80% agreement rule, never lowered because output looks empty;
- no public contributor count, identity, precise location or individual timestamp;
- short-lived output;
- Stop, Ghost Mode and deletion committed before the snapshot exclude the contribution;
- moderation and abuse controls remain mandatory.

### Truthfulness

ADR 0055 aggregation **reduces exposure but is not anonymity**. Participation can be inferred when an attacker knows several participants, accounts collude, the group is small, one party controls several accounts, or outside observations can be correlated. No UI copy, document or code comment may claim anonymity. After a summary is published, Routiqo cannot remove one person's influence from it. Withdrawal applies before the snapshot and to future summaries, and the product must say so.

### Identity constraint

Sybil resistance must not be solved by collecting more identity data. No government ID or Aadhaar, permanent device fingerprints, long-term location history, hidden cross-session tracking or unnecessary personal data. A future privacy-preserving eligibility concept, working name `ParticipationEligibility`, may evolve separately from the aggregation protocol. The goal is "one legitimate traveller, bounded influence". It is not built now.

### Pilot evidence required before any production proposal

| Area | Target |
|---|---|
| Coverage | At least 20% of eligible peak-hour windows produce a usable summary. This is a measurement, never a reason to lower thresholds |
| Overall correctness | At least 95% agreement with an independent reference traffic state |
| Dangerous false reassurance | 0%, measured separately from false congestion (for example, "moving normally" shown during severe or stationary congestion) |
| Freshness | At least 95% of displayed summaries within the intended freshness window; stale output disappears automatically |
| Withdrawal | 100% of the defined withdrawal suite passes |
| Contribution isolation, daily quota, publication threshold | Enforced under concurrency and verified in PostgreSQL |
| API and telemetry leakage | No contributor IDs, counts, coordinates, individual timestamps or raw metadata in APIs, logs, analytics, traces or metrics |
| Abuse and Sybil | Adversarial suite covers coordinated accounts, repeated account creation, synchronized false signals, deletion/recreation, rapid identity switching, replay, concurrent submissions, races, quota bypass, verification deletion races and window-boundary attacks; the system fails safe |
| User understanding | At least 90% of pilot participants understand that sharing is optional, Stop/Ghost semantics, aggregation, no public identity, no absolute anonymity, and that published summaries may not be reversible |
| Performance | Share, aggregation, publication, read, Stop/Ghost propagation and cleanup latencies are measured; heavy work stays off user request paths without breaking snapshot or withdrawal semantics |
| Operations | Observability without privacy leakage, incident response and rollback |

### Feature-flag safety

Community-derived public LIVE is **off by default and fails closed**. Missing, malformed or ambiguous configuration leaves it disabled. Automated tests must prove this, and no API, UI, fallback, debug endpoint or operational tool may expose it while it is off.

## Consequences

- V1 launches without community traffic.
- The LIVE list shows official alerts and each person's own private signals.
- ADR 0055 work continues under the priorities P0 (production isolation), P1 (correctness, withdrawal, leakage, concurrency, adversarial tests) and P2 (metrics, performance).
- ADR 0054 no longer blocks any Routiqo work.
