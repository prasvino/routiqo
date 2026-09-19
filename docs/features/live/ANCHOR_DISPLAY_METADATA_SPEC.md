# Private route-area display metadata

Status: implemented, tested and independently reviewed, 2026-09-19. This is a catalog prerequisite
for readable private Quick Signal choices, not a choice API or public publication.

## Narrow implementation

Extend the existing immutable operator-selected route-anchor catalog with optional
`displayLabel` text per anchor. Preserve the existing four required JSON fields
(`id`, `longitude`, `latitude`, `categories`) and accept only one additional known
field, `displayLabel`. Missing means no usable display metadata; explicit null,
wrong types, duplicate keys and every other unknown field remain invalid. Keep
the 256 KiB file bound, 512-anchor bound, strict UTF-8 and generic cause-free errors.
Existing unlabeled catalogs remain valid for existing internal route binding.

The domain stores an immutable optional label and retains a compatible three-
argument anchor constructor for unlabeled callers. Reject null optional values.
For a present label, require 1..80 Unicode code points, no leading/trailing space,
and no control, format/bidi-control, surrogate, unassigned, private-use, line or
paragraph separator characters. Only ordinary U+0020 is accepted as a separator;
Other Unicode letters, marks, numbers, punctuation and symbols remain supported.
Do not silently trim, normalize, truncate or convert labels to IDs. Text is plain
text, never HTML/Markdown, a URL, a provider search query or an automatic link.

All anchor/catalog/loader diagnostics remain redacted, including labels. Resolver
output, stored context, grant/receipt DTOs, existing browser APIs and client models
remain unchanged. No new endpoint, migration, feature flag, provider request,
dependency, catalog fixture in production or geographic fallback is introduced.

Operators must use reviewed public route-area names, not private addresses or
traveller-derived labels. Syntax validation cannot establish geographic truth,
public suitability, uniqueness or trust. Some permitted Unicode marks or letters
can still be visually inconspicuous or confusable; operators must review actual
readability and disambiguation. This is not a comprehensive invisible-text filter.
Catalog labels/categories/locations are
part of catalog content: changes require a fresh version and a coordinated
consistent replica rollout. Existing version/provenance fences remain mandatory.

## Private choice boundary — internal reader implemented; exposure pending

ADR 0043 and `PRIVATE_ANCHOR_CHOICES_SPEC.md` implement the internal read-only
authority and minimized snapshot below. No Spring wiring or browser surface is
added. Request quotas, transport guards and contribution UI remain future work.

- Add a separate owner-scoped read operation; do not broaden ADR 0034's minimal
  route-context DTO or issue grants just to populate choices.
- Under the established account/owned-journey authority, require active journey,
  current on-consent, unexpired context and matching non-empty catalog provenance.
  Return only exact context identity/expiry and its authorized label/category
  choices, never precise coordinates, geometry, endpoints or the whole catalog.
- Missing labels, mismatched catalog or unrecognized context anchors deny useful
  choice readiness; no UUID/coordinate/generated-name fallback. Labels are not
  evidence, physical-presence proof, a safety rating or public eligibility.
- Reads consume only bounded read-request quotas; no grant, acceptance, provider
  or contribution budget is consumed. They must remain no-store and default off.
- Final issue/accept must recheck current authority. Existing issuance accepts an
  anchor ID and can legitimately issue against a newer context containing that
  anchor; it does **not** provide an exact displayed-context CAS. Before UI work,
  specify a coordinated exact-context issuance precondition or an explicit grant
  mismatch/discard policy. A read snapshot alone cannot supply that guarantee.
  ADR 0044 now adds the internal expected-context method; the legacy HTTP path
  remains unchanged and a guarded browser contract is still required.
- Add explicit client lifecycle cancellation, context/grant matching, expiry,
  unknown-write recovery and no automatic contribution replay. This is a separate
  reviewed API/UI phase; no public privacy or anti-Sybil gate is relaxed.

## Verification

Test old catalog compatibility, optional presence, immutable metadata, Unicode and
80-code-point boundaries, malformed/blank/oversized/control/bidi labels, duplicate/
unknown/wrong-type/null fields, file byte limits and redacted errors/toString.
Retain resolver/configuration tests and run Java check/bootJar before completion.
No TypeScript changes are needed for this internal metadata slice.
