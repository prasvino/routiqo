# Data retention and deletion
Current implementation: catalog content plus local plans/saved destination IDs; opt-in Google account identifiers, bounded opaque sessions, server journey lifecycle records and account-bound local outbox/snapshot caches. Users can remove planning data separately from account deletion. Browser account deletion retires its local journey partition to prevent late workers recreating it; sign-out preserves queued work. Other devices retain local caches until their own storage is cleared; do not claim remote erasure of offline device data. No messages, media or public presence are stored yet.
Temporary Photon place results and calculated routes are not persisted or backed up. Optional one-time browser location stays in the route form and is sent to Valhalla only when calculating. Do not enable permanent provider result storage without a separate reviewed decision.

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

## Private Live evidence lifecycle

QUICK_SIGNAL_RECEIPT_SPEC.md defines explicit bounded retention and terminal
withdrawal/supersession. ADR 0028 implements durable private grants and receipts;
ADR 0035 adds a default-off owner command API with 15-minute evidence and 24-hour
receipt retention. Withdrawal stops temporal evidence eligibility but retains the
private immutable fingerprint until purge; it is not physical deletion. These
private transport lifetimes do not approve public publication or moderation holds.

Logical expiry is enforced independently of cleanup. ADR 0036 specifies default-off
bounded maintenance using the existing expiry adapters. Production activation,
backlog capacity, failure alerting and physical database/backup lifecycle remain
operational gates. A missing receipt never resets a consumed command grant.
Proposed public signal/client lifetimes and unresolved moderation holds remain
owned by ROUTIQO_LIVE_SPEC.md.
ADR 0037 specifies an independent abuse ledger bounded to 20 slots per account,
containing acceptance times, opaque anchors and categories. Charges expire
logically after one hour and are removed by bounded maintenance; physical cleanup
may lag. Withdrawal, journey completion and receipt purge must not reset this
budget. Account deletion cascades the ledger. No precise route/GPS or device
fingerprint is added. Publication trust remains unresolved (ADR 0038).
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
Expiry, Ghost withdrawal, block changes and deletion must invalidate derived
projections/caches, not only the original row. Do not retain raw location history
or put Live evidence into journals, backups, analytics or journey outboxes.

Memory-only rows expire locally; remote changes cannot erase previously delivered
information from disconnected clients immediately. Clear on local account/Ghost/
journey-end transitions and reauthorize on reconnect. Document physical deletion,
backup retention and lawful moderation exceptions explicitly before release.
