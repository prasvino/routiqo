# ADR 0062: Explicit account planning backup

Status: accepted, 2026-09-25.

## Context

Plans and saved places are browser-local (ADR 0005 keeps local drafts separate from live journeys). Signed-in travellers lose them when they change device. The first-release list asks for broader cross-device restoration without overwriting pending local work. Plan text is free-form and can contain home or work addresses.

## Decision

Store **one owner-only planning document per account**, written only on an explicit traveller action. The document is versioned and written by compare-and-swap with exact mutation replay; the write semantics are the same as journal annotations (ADR 0017/0058). Restoring **merges** into local state using the existing backup rule (keep local plans, add new IDs, combine saved places). It never replaces local data.

Rules:
- A server flag and a UI flag (`ROUTIQO_PLANNING_BACKUP_API_ENABLED`, `NEXT_PUBLIC_ROUTIQO_PLANNING_BACKUP_UI_ENABLED`) are both off by default.
- The document limit is 256 KiB. Only `POST /api/v1/planning` gets a larger body limit; all other browser POSTs stay at 20 KiB.
- The server validates the structure strictly. The client re-validates semantics on read and rejects the whole document if anything is invalid.
- Deleting the account deletes the copy (cascade). Signing out does not.

Contract and behavior: `docs/features/journey/ACCOUNT_PLANNING_BACKUP_SPEC.md`.

## Alternatives considered

- **Automatic background sync with per-plan rows.** It gives better merge granularity. It also means automatic upload of possibly sensitive endpoints, tombstones, and a conflict UI for every plan. Deferred until the explicit copy shows real use.
- **Per-plan endpoints.** They avoid the larger body limit, but explicit whole-state backup would take many writes and partial failures would be hard to explain.
- **Replace-on-restore.** Simpler, but it can destroy local-only work. Rejected: merge only.

## Security and privacy impact

The server now keeps plan text, which may hold precise endpoints. This is mitigated by:
- explicit upload only;
- owner-only access and redacted logging;
- no secondary use;
- traveller-controlled removal and account-deletion cascade;
- rate limits and bounded size.

Database backup retention remains an open release gate.

## Operational impact

- One new table (V28), with no background jobs.
- It is only active when the flags are on and the `persistence` and `web-auth` profiles are running.
- Rollback: turn off the flags. Stored rows stay until the traveller removes them (with the flags back on) or deletes the account.

## Consequences

Cross-device recovery becomes possible without automatic sync. Automatic sync, native controls and per-plan conflict resolution remain future work, and each needs its own specification.
