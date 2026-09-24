# ADR 0058 — Native durable trip journal editing

Status: accepted for implementation, 2026-09-24. Production/service/device
validation remains separately gated.

Extend ADR 0017's private completed-trip journal to Android using the existing
native bearer boundary and existing JournalService compare-and-set/replay rules.
The native POST shares the browser journal account-write rate bucket. It does not
introduce a new journal authority, server table or public endpoint.

Use the existing app-private SQLite database for a separate bounded journal
partition, rather than placing annotation mutations in the journey lifecycle outbox.
Journals are authored durable text; a deliberate account save must first commit its
exact version/text/mutation identity on the device. Journal delivery is explicit,
including retries; reconnect must not automatically publish old edits. The existing
retired-account marker serializes deletion with every journal transaction.

Retain at most 20 drafts and 20 confirmed journal snapshots, with a 1 MiB partition
limit. Never evict an unsent draft or its confirmed base. Cache versions are monotonic;
acknowledgements and conflict discards compare the exact reviewed local mutation and
snapshot. A bounded saved-journal list keeps local work discoverable independently
of server history pagination. Corruption is reported without overwriting stored data.

Logout retains device drafts under their original account. Only the currently
verified account can expose them; a cold offline app cannot use a stored account ID
as authentication. Confirmed account deletion atomically retires and removes journal
content alongside journey storage. SQLite logical deletion is not a claim of forensic
erasure of filesystem/WAL blocks; physical-device, platform encryption and backup
behavior remain release verification requirements. Journal data is excluded from
planning exports and logs. Reuse platform app-private storage and existing Android
backup exclusion; no credential is stored in this journal partition.

The pinned Expo SQLite implementation uses `files/SQLite`. The pinned SecureStore
backup resources explicitly include only shared preferences (excluding SecureStore)
for legacy backup, cloud backup and device transfer, so they exclude that SQLite
directory and its sidecars. A dependency regression test checks this relationship;
final APK resource/manifest inspection must confirm the rules are actually packaged.
Android documents that [explicit include rules replace the default file set](https://developer.android.com/identity/data/autobackup#IncludingFiles).
Do not assume that `allowBackup=false` alone covers every device transfer path.

Keep editor-local text separate from epoch-keyed browsing state. Verified rotation
for the same account cancels stale operations and invalidates remote freshness while
preserving typing. Losing/changing the verified account clears private views. Native
modal close and Android Back share unsaved-edit protection. Only explicitly saved
device drafts are guaranteed to survive process termination.

See `docs/features/journey/NATIVE_JOURNAL_EDITING_SPEC.md` for precise APIs, limits,
failure behavior and tests. No media, sharing, LIVE activation, provider integration
or cross-device planning synchronization is authorized by this decision.
