# ADR 0001: Monorepo and foundation stack
Status: accepted from user-supplied master context, 2026-09-06.
One pnpm/Turbo TypeScript workspace and a separate Gradle Kotlin DSL Java build. Expo mobile, Next.js consumer/admin, Java 25/Spring Boot core/realtime/workers. Strict TypeScript everywhere.
PostgreSQL/PostGIS, Redis and S3-compatible local storage. No Kafka, Kubernetes, OpenSearch or ClickHouse.
Alternatives: previous Wayfind split repos/Vite/Azure are historical and do not match Routiqo's specified architecture.
Consequences: atomic contract/client changes, multiple runtime toolchains, native UI testing remains separate.

