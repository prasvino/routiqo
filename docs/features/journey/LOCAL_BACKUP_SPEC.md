# Local planning backup and restore

## Goal
Let web users recover or move local plans without an account. Profile provides a JSON download and a file-based restore preview. Shared validation/merge functions can support a later native file-picker integration.

## Boundaries
No server request, cloud sync, login, automatic upload, or device sharing. Download explicitly discloses that the unencrypted file includes route names, dates, notes and saved places. Only known planning fields are exported; unknown object fields are discarded. The source file is not retained after the dialog closes.

## Flow and rules
- Download freshly reads storage, validates and wraps data in a Routiqo format/version envelope.
- Restore accepts at most 512 KB, validates all records and versions, then previews plan/place counts before any write.
- Merge adds missing IDs and saved places; an existing plan always wins on matching ID. No replace-all option.
- Restore re-reads current storage at apply time. Combined state must fit the existing 100-plan/100-place limits; failures write nothing and remain visible.
- Invalid JSON, unsupported formats, duplicate plan IDs, malformed dates, oversized input and quota/storage failures must never clear data or report success.
- Restore is idempotent. Re-importing the same backup adds nothing.
- The browser's existing localStorage cross-tab behavior is retained; this is not a server transaction or multi-device sync protocol.

## UI and verification
Use existing Profile settings rows and accessible dialog. One file input, preview, cancel and restore action. Warm, restrained existing design; no new motion or dependencies. Tests cover roundtrip, unknown-field removal, invalid/versioned input, limits, preserved edits and repeated import. Inspect rendered dialog and error/empty states. Native backup/restore remains deferred until native file tooling is selected and device tested.

Native extension: NATIVE_BACKUP_SPEC.md adds selectable/shareable backup text and pasted-JSON restore using the same format. Native file picker and real-device verification remain pending.
