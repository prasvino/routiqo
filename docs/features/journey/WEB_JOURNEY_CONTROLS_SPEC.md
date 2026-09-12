# Web journey controls

Visual thesis: retain the calm Trips surface, shared typography and restrained accent, with a compact journey workspace above planning drafts. Content order: current journey status, primary start/finish action, pending-action recovery, then unchanged local planning. Interaction thesis: use existing modal focus/escape behavior, button feedback and reduced-motion-safe status changes.

Only explicit user actions enqueue journey commands. Planning dates never start or finish journeys and drafts are never uploaded. Start uses a fresh stable UUID and kind only; no endpoints or notes leave the device. Durable storage succeeds before any transport attempt or success message. Completion must refer to a known active snapshot or pending start. Completed snapshots and repeated pending completion are no-ops. One locally active or pending journey prevents another start. All checks and writes share one IndexedDB transaction so tabs cannot race a second start through stale rendered state. Server ownership and single-active constraints remain authoritative across devices.

The workspace must distinguish pending start, server-confirmed active, pending completion, confirmed completion, authentication pause, conflict, rejected and unavailable storage. Do not label a queued finish as completed. Session verification precedes access from controls, and every send rechecks it. Ordinary logout preserves pending work, while explicit account deletion clears the account partition. Missing Google configuration keeps the workspace unavailable without changing local planning.

Implement storage invariants and tests first; then mount controls and bounded visible-page dispatch. Reconciliation must obtain authoritative state before releasing conflicts. No automatic dropping of commands. Real OAuth and native device behavior remain external verification prerequisites.

Implementation pass: workspace mounted above Trips plans; configuration/session gating, start modal, finish and explicit retry use durable storage and bounded dispatch. Offline actions can use the account verified when the workspace loaded; no network request is sent until account verification succeeds again. Status distinguishes queued work from confirmed active state. Current preview disabled-state desktop rendering verified; authenticated UI, mobile layout and reconciliation remain to verify. Automatic reconnect dispatch is not yet mounted. No claim of real Google sign-in.

Foreground recovery update: while Trips is mounted, an unblocked pending head gets a timer honoring both retry time and lease expiry, a one-minute fallback, and online/visible-page event listeners. Hidden/offline pages do not send. Unmount cancels the next batch command while preserving acknowledgement already in flight. Explicit authentication recovery is still required for blocked heads. Focus refresh cannot race an in-flight mutation; account changes close the start dialog. These paths require authenticated component/event QA before release.
Conflict inspection: Check server status performs an owner-bound GET, validates identity and response shape, and displays active/completed/missing/unavailable. It never clears commands. Automatic reconciliation and authenticated component QA remain pending.

Confirmed reconciliation now atomically imports the matching server result and acknowledges only the still-blocked matching head. Different kind, incomplete finish, stale head and inconsistent lifecycle preserve work. Recent server restoration fetches at most20 owner-bound records and merges snapshots transactionally without acknowledging pending actions; inconsistent batches roll back. This is bounded recent history, not full account synchronization. Account deletion racing a history fetch still needs a partition tombstone/generation guard before release.

Component verification uses pinned development-only @testing-library/react16.3.0 and jsdom26.1.0 in the web workspace. Vitest includes web TSX tests with the installed Vite8 Oxc automatic JSX transform. Tests cover configuration gating, save failure, account-switch dialog closure, offline start/finish, reconnect, blocked retries and completed feedback. These simulate the server boundary, not live Google OAuth. Disabled-state layout verified at390px; authenticated visual QA remains pending.
## Offline action availability

For an already verified account, an offline banner explains that start/finish can
be saved locally and eligible queued work retries when connected with this view
open. Restore recent journeys, Retry saved actions and Check server status are
disabled offline; server history/conflict reads also recheck connectivity in their
handlers. Reconnect enables these reads without automatically restoring history or
checking conflicts. Existing due-time, lease, authentication and visibility gates
continue to govern queued dispatch. Blocked actions are never advertised as sent.
