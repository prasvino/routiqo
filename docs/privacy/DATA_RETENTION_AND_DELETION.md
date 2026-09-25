# Data retention and deletion
Current implementation: catalog content plus local plans/saved destination IDs; opt-in Google account identifiers, bounded opaque sessions, server journey lifecycle records and account-bound local outbox/snapshot caches. Users can remove planning data separately from account deletion. Browser account deletion retires its local journey partition to prevent late workers recreating it; sign-out preserves queued work. Other devices retain local caches until their own storage is cleared; do not claim remote erasure of offline device data. No messages, media or public presence are stored yet.
Temporary Photon place results and calculated route geometry are not persisted or backed up. Optional one-time browser location stays in the route form and is sent to Valhalla on explicit calculation or private route preparation. Do not enable permanent provider result storage without a separate reviewed decision.

Explicit web map display uses MapLibre and the configured same-origin `/maps/` resources. Browser HTTP caches can retain viewed resources; earlier Mapbox versions may have left CacheStorage tiles or localStorage event metadata. Account deletion does not clear browser map caches. Map display discloses this before loading. Clear browser site data for local removal, preserving desired planning backups first. Do not claim zero browser persistence or downloaded/offline navigation from browser caches.
Browser and native local planning storage must validate shape/version and expose storage failures. No silent cloud upload.

Account planning copy (ADR 0062, default off): when a traveller explicitly saves, one owner-only copy of their plans and saved destination IDs is stored in `account_planning_copy`. It is kept until they remove it in Profile or delete the account (database cascade). Signing out keeps it. It is never read by other accounts, LIVE, discovery or AI, and its content is never logged. Adding it to a device merges into local storage and never deletes local plans. Operational database backups follow the open backup-retention gate.
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

Private route preparation uses the existing bounded latest-row context (ADRs
0027/0032/0034): derived private anchor IDs and optional catalog provenance, not
precise endpoint/geometry history. Browser observations and acknowledgements remain
in memory only; clearing them does not delete server context. A failed, cancelled
or empty bind is not a deletion. Logical expiry and journey completion invalidate
the context under existing server authority; cleanup and account deletion retain
their existing semantics. The browser integration adds no retention or backup.

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
Expiry, Ghost withdrawal, block changes and deletion invalidate current private
evidence, discoverable presence and viewer-local rows. A future ADR 0054 frozen
public input would be an explicit, disclosed exception: it cannot be retracted
or cause a source-dependent published-row change. This is not enabled and needs
approved retention and deletion terms. Do not retain raw location history
or put Live evidence into journals, backups, analytics or journey outboxes.

ADR 0055's distinct V3 community traffic summary is authorized for staging
implementation only. Its database design logically expires source candidates
24 hours after their five-minute window ends, stops serving a projection at
window end plus ten minutes, and schedules bounded removal of expired projection
content within another 24 hours. Daily debit rows have a short cleanup window;
report and suppression-audit rows have a 30-day staging expiry; terminal
decision tombstones remain durable to prevent reopening.
These are implementation settings, **not approved production retention or legal
deletion terms**. Account-bound retry, decision/audit and backup horizons need
separate review and operating procedures. Source cleanup and account deletion
must not cascade-delete a canonical summary. A Stop, Ghost Mode or deletion
committed after the publication snapshot may be too late to exclude that
candidate, and previously captured output cannot be erased. This V3 contract
does not reuse V18 intent or V22 frozen input as consent, and no V3 production
flag is approved for activation.

Memory-only rows expire locally; remote changes cannot erase previously delivered
information from disconnected clients immediately. Clear on local account/Ghost/
journey-end transitions and reauthorize on reconnect. Document physical deletion,
backup retention and lawful moderation exceptions explicitly before release.

ADR 0045's private choice snapshot adds no durable browser or server copy. Its
bounded owner response is no-store and validated for current expiry; labels,
versions and category sets remain request-scoped. Existing catalog retention,
route-context expiry, grant lifetime, receipt retention and deletion rules remain
unchanged. Choice reads reserve only an existing durable request-rate row;
expected issuance shares legacy issuance budgets and adds no new grant lifetime.

ADRs 0046/0047 reuse existing consumed grants and terminal receipts for command
stopping. No extra tombstone, receipt, evidence copy, budget refund or new
retention interval is created. Original receipt/grant timestamps and terminal
supersession are preserved. An expired retained receipt is not renewed by stop;
cleanup may make later retries generically unknown. A consumed grant without a
receipt cannot be treated as proof that no past acceptance occurred. Existing
account deletion cascades and bounded expiry rules continue to apply.

The private Quick Signal browser interface keeps at most five command recovery
records in JourneyWorkspace memory, including terminal notices. Uncertain commands
retain only account/journey/command identity, phase and operation authority;
confirmed receipts add minimized status/timestamps. No route labels, observation
values, exact endpoints or fingerprints enter this collection, browser storage,
journey outbox, journal, backup or analytics. Confirmed receipt metadata expires
at the server retainUntil or 24 hours of local elapsed time from capture, whichever
comes first; elapsed-time checks prevent wall-clock rollback extending that bound.
Expiry removes metadata, not the only known stop handle, and never proves server
deletion. Capacity blocks issuance rather than silently evicting recovery.

Same-account identity refresh temporarily hides controls while preserving memory.
Ghost, route changes and journey completion invalidate contribution choices but
preserve known stop handles. Account change, logout and workspace departure clear
the collection. Explicit removal requires acknowledging that it only removes
local recovery and does not stop server work. Navigation/unload warnings are best
effort; no cross-page or reload recovery is promised. An empty collection does
not prove there are no outstanding server commands. See
`../features/live/BROWSER_QUICK_SIGNAL_UI_PLAN.md` for the lifecycle contract.
