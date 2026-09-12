# ADR 0023: Server-owned route context and short-lived signal admission

Status: accepted internal design; registration, storage and HTTP issuance pending.
This resolves the admission portion of L0.2, not aggregate publication approval.

## Context

Current persisted journeys contain lifecycle/ownership, not validated route
associations. Browser route estimates and typed places are memory-only. Accepting
arbitrary client anchor IDs would permit remote report spam and route probing.
Journey ownership alone does not prove physical presence or anchor relevance.

## Decision

Represent a future server-owned LiveRouteContext as an internal context UUID,
actor/journey IDs, monotonic revision and at most 128 approved public/coarse anchor
IDs. Store neither raw endpoint coordinates nor route geometry in this model.
Even coarse anchor sets can reveal route intent: never expose them as an enumerable
public list, log them, or treat them as anonymous data.

Future context registration must be computed by the server from a validated route
result and a reviewed anchor registry, accessed through domain application
interfaces. No request-supplied arbitrary set becomes authoritative. Registration
requires the owned active journey and bounded quotas. Neither route intent nor
GPS supplied by a client is physical-presence proof. A separate retention/storage
decision must precede persisting these additional route associations.

Represent SignalAdmission as an internal actor/journey/context/anchor binding,
route revision, consent generation, category set and half-open issued/expiry window
of at most 90 seconds. It is not a public bearer credential or signed token.
Future issuance references current server-owned state and authorized anchor
categories. Expiry never exceeds configured policy; an expired admission is not
renewed by retry. Changing route revision, consent generation or journey lifecycle
invalidates old admissions. Re-enabling sharing does not revive the old generation.

The application policy evaluates the independently authenticated actor and current
authoritative Journey, PresenceConsent,
LiveRouteContext and admission snapshots together, with server time. It denies
null/missing, wrong authenticated actor, wrong-owner, wrong-journey, inactive, future, expired, revoked,
revision-mismatched, unregistered-anchor and disallowed-category inputs. A
temporally valid admission must have been issued at or after journey start.

Domain constructors establish structural validity only. Production callers must
load the snapshots through owning-domain application interfaces and couple the
check with accepted writes using a transaction/version check. Reusing these
objects from a client payload or checking only once outside the write boundary
is not authorization. No correctness depends on a process-local map or lock.

## Scope and consequences

The first implementation is immutable models plus an application policy. No
registrar, admission issuer, storage, token exchange or public endpoint is enabled.
Its tests demonstrate input consistency and invalidation, not forged-token
resistance, physical location, persistent concurrency or publication anonymity.

Before storage and ingestion, define admission receipts and route-context linkage
for accepted evidence, idempotent replacement, context revision CAS, withdrawal,
retention/deletion and moderation holds. QuickSignal alone does not retain route
context/revision and must not serve as the complete durable acceptance record.

Cohort publication remains separately gated: fixed partitions/windows, sparse and
block-safe suppression, independent evidence criteria, colluding/repeated-query
analysis and bounded cached projections. No count or moment may be exposed merely
because an admission check succeeds. See ROUTIQO_LIVE_SPEC.md and PRESENCE_SPEC.md.
