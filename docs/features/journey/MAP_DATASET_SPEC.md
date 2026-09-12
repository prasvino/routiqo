# Regional dataset inventory and integrity

Before regional service provisioning, stage operator-reviewed artifacts under the
ignored `map-data/` directory. Run `pnpm maps:check -- <directory>` against a
directory containing `manifest.json` and exactly three inventoried artifact roles:
`valhalla`, `photon`, `tiles`. Artifacts can be opaque export/archive files; this
checker does not extract, execute, download or claim provider format compatibility.

Manifest version 1 contains only: `version`, `region`, `bounds`, `artifacts`.
Region is a short lowercase slug. Bounds are `[west, south, east, north]`, finite,
ordered, geographic, with longitude span below 180 degrees. These bounds must
later match the routing runtime settings and be reviewed against actual datasets.
An inventory alone cannot establish coverage or that every road is routable.

Each artifact contains only `role`, `path`, `bytes`, `sha256`, `source`,
`attribution`. Paths are portable relative paths without traversal, Windows drive
or alternate stream syntax, reserved device basenames, backslashes, or empty segments. Resolve real paths
and reject files outside the selected directory. Every artifact must be a regular
file and have a distinct resolved path. Source is an HTTPS provenance URL without
credentials, query or fragment; it is never fetched. Attribution is bounded plain
text with no control characters. Keep license notices within the staged artifacts;
operator review still establishes licensing and the trusted source of hashes.

Bound manifest reads to 64 KiB, each declared artifact to 256 GiB, and stream hashes
with a five-minute per-file deadline and byte count enforcement. Require all files
and sizes to pass before hashing any. Detect ordinary file mutation during hashing
using size and modification time checks. This assumes a trusted operator-controlled
filesystem, not a defense against a malicious concurrent filesystem owner.

Output only fixed success/failure text; do not print local paths, provenance URLs,
hashes, attribution or provider data. Missing files, invalid manifest, escaping
paths, timeouts, size/hash mismatch fail nonzero. Success explicitly says integrity
only and leaves service readiness unverified. No environment settings are written,
services started or existing data replaced. Runtime does not yet consume this
manifest. Signed provenance, format checks, coordinated version updates and live
English/Tamil search and route-quality fixtures are subsequent gates.
