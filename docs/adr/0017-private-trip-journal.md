# ADR0017: Private completed-trip annotations

Status: accepted for the first journal slice, 2026-09-12.

The product calls for trip journals while keeping commute summaries distinct. Derive a minimal journal from each owned completed TRIP lifecycle and persist only optional authored title/notes in a separate journal domain. Missing annotations read as version0 without background writes. No provider route geometry, coordinates or generated travel metrics are copied. Sharing, photos and AI enrichment are not enabled.

Use JourneyService as the eligibility boundary, owner-filter all annotation access, and use foreign-key cascades for deletion lifetime. Optimistic versions and latest mutation identifiers prevent silent overwrites across clients while allowing exact response-loss retries. The server cannot make older retries overwrite later edits. The browser editor must preserve unsent text durably before writes; backend readiness alone is not completion of offline journal editing.

See TRIP_JOURNAL_SPEC.md for input limits, status codes, retention, verification and the explicit distinction between clearing annotation content and deleting journey/account records. External sharing will need its own visibility, redaction and revocation contract before implementation.
