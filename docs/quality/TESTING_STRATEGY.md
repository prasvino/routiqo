# Testing strategy
Strict TypeScript + ESLint + Vitest for shared behavior; generated contract drift checks; Next production builds; Expo export/typecheck.
Java: domain tests, Spring HTTP/security integration tests, ArchUnit boundaries. Multi-instance tests are mandatory before realtime exposure.
Manual rendered UI checks complement automated checks. Native bundling does not prove Android device performance.
Foundation tests must exercise validation, persistence corruption/version handling, duplicate saved IDs, plan changes, lifecycle invariants and privacy suppression.

