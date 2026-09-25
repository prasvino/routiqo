# Data retention and deletion
Current implementation: catalog content plus local plans/saved destination IDs; opt-in Google account identifiers, bounded opaque sessions, server journey lifecycle records and account-bound local outbox/snapshot caches. Users can remove planning data separately from account deletion. Browser account deletion retires its local journey partition to prevent late workers recreating it; sign-out preserves queued work. Other devices retain local caches until their own storage is cleared; do not claim remote erasure of offline device data. No messages, media or public presence are stored yet.
Temporary Photon place results and calculated route geometry are not persisted or backed up. Optional one-time browser location stays in the route form and is sent to Valhalla on explicit calculation or private route preparation. Do not enable permanent provider result storage without a separate reviewed decision.

Explicit web map display uses MapLibre and the configured same-origin `/maps/` resources. Browser HTTP caches can retain viewed resources; earlier Mapbox versions may have left CacheStorage tiles or localStorage event metadata. Account deletion does not clear browser map caches. Map display discloses this before loading. Clear browser site data for local removal, preserving desired planning backups first. Do not claim zero browser persistence or downloaded/offline navigation from browser caches.
Browser and native local planning storage must validate shape/version and expose storage failures. No silent cloud upload.
Before public launch define category-specific retention for raw GPS, presence, messages, reports, media, journals, AI outputs, sessions, backups and providers. No default indefinite precise movement retention.
Account deletion must revoke sessions promptly and be tested end to end; do not reuse the contradictory Wayfind reactivation specification.

Native journey storage retirement retains only the deleted account UUID in a local marker table, atomically removing its queue and snapshots. This marker prevents delayed work from recreating data and remains until app storage is removed. It contains no journey content, credentials or dates and is excluded from planning backups. This storage primitive is not yet connected to native account deletion. Ordinary sign-out must preserve the partition without retiring it. SQLite logical row removal does not establish physical page erasure or OS-backup deletion; those remain device/release validation requirements.

The native session vault stores one opaque credential and account/expiry metadata through Expo SecureStore, separate from planning and journey SQLite data. It is not yet populated by a native sign-in flow. Credentials are excluded from Android backup configuration and use device-only unlocked Keychain accessibility. Local clear removes the record but does not revoke the server session; future logout must combine both. Expired records are not returned as sessions. Do not assume iOS uninstall erases Keychain records; reinstall and physical-deletion behavior require device verification.

## Open-source migration target (ADR 0021)

Web MapLibre migration is implemented; self-hosted services and native rendering remain pending. Legacy Mapbox caches may survive the SDK change. Changing providers does not authorize new
retention of search text, endpoints, routes or location history. Before enabling
native downloads, define disk quotas, update/deletion semantics, region metadata
privacy and account-switch/account-deletion handling. Validate renderer and HTTP
cache behavior; local route clearing must not claim to erase resources it cannot
remove. Keep public basemap content separate from account-specific route records.

## Pilot content lifetimes

Planned for the pilot (see [`docs/PRODUCT.md`](../PRODUCT.md)); not implemented
unless [`BUILD_STATUS.md`](../quality/BUILD_STATUS.md) says so. Exact per-type
lifetimes and the "Still true?" extension remain open questions.

| Content | Lifetime |
| --- | --- |
| Traffic and queue signals | about 1–2 hours |
| Food, fuel and restroom posts | about 24 hours |
| Festival route room | the event window |
| After expiry | top tips per Spot kept as highlights; no full chat archive |
| Ask Ahead questions and answers | expire with the Spot content they are pinned to |
| Spot passage records | deleted within 24 hours; logged only as outcome codes |
| Route guides | kept until the author deletes them |

- Expiry uses server time. "Still true?" confirmations extend a post's life by a
  bounded amount; silence lets it expire. Client clocks and retries cannot
  refresh content, and nothing expired is shown as current.
- Offline posts, signals and answers queue with capture time and an idempotency
  key; the server rejects them once the type's lifetime has elapsed since capture
  and shows accepted items with their capture time, never as new.
- Highlights are a small curated subset per Spot, not a retained copy of every
  post or chat line.
- Route guides strip exact home, office and start/end addresses before
  publishing; the author's private journey route stays private.
- Authors can delete their own posts. Account deletion removes the account's
  posts, voice notes, signals, Ask Ahead questions and answers, Spot-passage
  records and route guides.
- Voice notes are stored in S3 through signed direct uploads and deleted with
  their post, by expiry, author deletion, moderation removal or account deletion.
- Ghost Mode, sign-out and account deletion clear queued social items on the
  device.
- Do not claim remote erasure of copies already delivered to other devices,
  including disconnected ones; local expiry limits their display.

## Moderation, restriction and block retention

ADR 0037 specifies an independent abuse ledger bounded to 20 slots per account,
containing acceptance times, opaque anchors and categories. Charges expire
logically after one hour and are removed by bounded maintenance; physical cleanup
may lag. Withdrawal, journey completion and receipt purge must not reset this
budget. Account deletion cascades the ledger. No precise route/GPS or device
fingerprint is added.
ADR 0039 retains one latest contribution-restriction revision/boolean per account
until account deletion. That monotonic state prevents old grants regaining authority
after restoration; it contains no assessment reference, evidence body or action
history. ADR 0040 implements a separate private directed-edge store: at most 100
rows per blocker, including unblocked revision tombstones, retained until either
account is deleted. Both account FKs cascade; no evidence or block-action history.
Reports remain pure lifecycle models plus an unimplemented intake proposal, not
a durable store or permission to retain moderation evidence indefinitely.

ADR 0041 introduces only minimized internal moderation command audit: opaque
operator/subject/request IDs, closed action/reason, restriction revisions/result
and server timestamps, with fixed 30-day logical expiry and bounded physical
cleanup. Either account deletion cascades identifying receipts. At most 1,000
physical receipts per operator are retained; full capacity denies new work rather
than discarding fresh audit. This internal engineering bound is not an approved
operational or legal evidence-retention policy. No source evidence is copied.
Separate operator-only action debit slots (at most 20) enforce a rolling hour and
are reusable or eligible for bounded cleanup after expiry; they contain no subject
and survive subject deletion. Logical expiry does not claim physical erasure.
Operator deletion cascades them. Finite action-specific operator grants last at
most 24 hours; stored expired grants are inert and may remain until replacement
or operator deletion. No account-owner role is provisioned by migrations.

Moderation of posts, voice notes and chat (hide, remove, restrict) must define
report and hidden-content retention before the pilot; hiding is not deletion,
and a moderation hold must not extend a content lifetime silently.

## Delivered copies

Memory-only rows expire locally; remote changes cannot erase previously delivered
information from disconnected clients immediately. Clear on local account/Ghost/
journey-end transitions and reauthorize on reconnect. Document physical deletion,
backup retention and lawful moderation exceptions explicitly before release. Do
not retain raw location history or put Spot-passage records into journals,
backups, analytics or journey outboxes.

## Archived private LIVE retention (default-off code)

The private LIVE code stays in the repository, default-off, and its retention
rules still describe that code:
private route context is a bounded latest row of opaque anchor IDs (ADRs
[0027](../adr/0027-durable-live-route-context.md)/[0032](../adr/0032-two-transaction-route-binding.md)/[0034](../adr/0034-default-off-browser-route-context-api.md));
grants and receipts use 15-minute evidence and 24-hour receipt retention, and
withdrawal is not physical deletion (ADRs [0028](../adr/0028-transactional-signal-storage.md)/[0035](../adr/0035-default-off-browser-quick-signal-api.md),
[`QUICK_SIGNAL_RECEIPT_SPEC.md`](../features/live/QUICK_SIGNAL_RECEIPT_SPEC.md));
logical expiry is enforced independently of default-off bounded maintenance
(ADR [0036](../adr/0036-default-off-live-expiry-maintenance.md)); a missing
receipt never resets a consumed command grant. Choice snapshots add no durable
copy, and command stopping reuses existing grants and receipts with no new
retention interval (ADRs [0045](../adr/0045-private-browser-signal-choice-boundary.md)–[0047](../adr/0047-private-browser-command-stop-boundary.md)).
Browser command recovery keeps at most five memory-only records bounded by
retainUntil or 24 hours of elapsed time (ADR [0048](../adr/0048-private-browser-signal-recovery.md)).
The unapproved ADR [0054](../adr/0054-irreversible-share-traveller-live-pilot-protocol.md)
frozen public input and the ADR [0055](../adr/0055-consented-community-traffic-summary-proposal.md)
V3 summary settings (24-hour source expiry, 30-day staging report/audit expiry,
durable decision tombstones) are staging implementation settings, not approved
production retention or legal deletion terms; no V3 production flag is approved.
Full detail is in the ADRs and in [`docs/archive/`](../archive/README.md). Reusing
signal storage, receipts, budgets and expiry maintenance for Spot signals requires
the pilot lifetimes above, not these private lifetimes.
