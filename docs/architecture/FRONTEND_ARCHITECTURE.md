# Frontend architecture

Planned Routiqo Live: an active-journey LIVE list within the existing four tabs.
The list and later map markers consume one authorized situation projection; neither
receives raw membership. Keep finite, accessible lists and coarse freshness rather
than avatar feeds or per-second announcements. All loading/empty/suppressed/offline/
expired/auth/uncertain-write states are specified in ROUTIQO_LIVE_SPEC.md. UI mocks
must not imply live population, verified presence or working realtime services.

Next.js consumer/admin apps are independent. Expo mobile uses native components. pnpm/Turbo orchestrates TypeScript only.
Generated API types live in api-client; platform-neutral planning/validation helpers in shared and validation; tokens in design-tokens.
Server state, durable local plans, UI state and high-frequency location/realtime state are distinct.
Initial browser persistence uses a versioned, validated local storage envelope for non-sensitive planning data. Native persistence uses SQLite. No tokens or live GPS are stored by this slice.
