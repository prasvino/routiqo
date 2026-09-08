# Frontend architecture
Next.js consumer/admin apps are independent. Expo mobile uses native components. pnpm/Turbo orchestrates TypeScript only.
Generated API types live in api-client; platform-neutral planning/validation helpers in shared and validation; tokens in design-tokens.
Server state, durable local plans, UI state and high-frequency location/realtime state are distinct.
Initial browser persistence uses a versioned, validated local storage envelope for non-sensitive planning data. Native persistence uses SQLite. No tokens or live GPS are stored by this slice.

