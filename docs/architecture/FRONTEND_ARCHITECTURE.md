# Frontend architecture

Planned Journey (see [`../PRODUCT.md`](../PRODUCT.md)): the Journey map is the
hero, with the route, "me" and the Spots ahead ordered by distance. The Spot panel
shows latest signals, posts, voice notes, Ask Ahead and a short temporary chat.
Map markers and lists consume the same authorized, expiry-aware Spot projection;
neither receives other travellers' positions, identities or membership lists. Keep finite, accessible lists
and coarse freshness ("3 reports in 20 min") rather than avatar feeds, traveller
counts or per-second announcements. Specify loading, empty ("no recent posts"),
offline/stale, expired, auth, permission-denied and uncertain-write states.
Android is the primary pilot client; web serves route guides and planning. UI
mocks must not imply live population, verified presence or working realtime
services. The archived LIVE list UI stays default-off.

Next.js consumer/admin apps are independent. Expo mobile uses native components. pnpm/Turbo orchestrates TypeScript only.
Generated API types live in api-client; platform-neutral planning/validation helpers in shared and validation; tokens in design-tokens.
Server state, durable local plans, UI state and high-frequency location/realtime state are distinct.
Initial browser persistence uses a versioned, validated local storage envelope for non-sensitive planning data. Native persistence uses SQLite. No tokens or live GPS are stored by this slice. Foreground location during a journey and on-device Spot-passage detection stay on the device.
