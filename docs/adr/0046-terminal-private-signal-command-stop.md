# ADR 0046: Terminal stop of a known private signal command

Date: 2026-09-19

Status: accepted; implementation tested and independently reviewed.

## Context

Transport abort and receipt withdrawal do not fence a delayed acceptance when no
receipt existed at withdrawal time. Contribution UI needs a server-side terminal
operation for a known issued command before it can truthfully offer cancellation.

## Decision

Reuse owned-journey/account serialization and existing consumed-grant / terminal
receipt state. In one callback, withdraw a retained owner receipt if it exists;
otherwise consume the matching existing owner grant. Preserve legacy APIs,
budgets, lifetimes and cleanup. Do not invent records for unknown commands or
claim that missing retained history proves no prior acceptance.

The exact minimized result, denial semantics and race/rollback acceptance are in
`../features/live/SIGNAL_COMMAND_STOP_SPEC.md`. ADR 0047 separately exposes this internal operation through guarded browser transport; no UI is included.

## Consequences

Stop can linearize before or after acceptance without reviving evidence. It is
idempotent while relevant private state remains, and is not physical erasure,
public revocation delivery, a blanket consent change or permission to retry writes.
