# Server journey lifecycle persistence

## Goal and scope
Implement the durable foundation required before exposing start/complete journey APIs. Java application service + PostgreSQL adapter + Flyway migration + real database tests. Existing local plans are separate drafts and are not uploaded or migrated.

## Model and invariants
Persist journey ID, owner ID, kind, active/completed status, start/completion timestamps only. No route endpoints, coordinates, presence or movement history. Exactly one active journey per owner, enforced by a partial unique index. Reuse the stable journey UUID for retries: starting the same ID/kind returns the original record, including after completion; changing kind conflicts. Completion is idempotent and owner-only. Lifecycle timestamps are assigned by an injected server clock, with microsecond precision matching PostgreSQL.

## Ownership and contracts
All repository reads/updates require owner ID. Application callers must eventually obtain it from verified identity, not HTTP payloads. Missing and other-owner reads/completions have the same not-found outcome. No new public HTTP endpoint is introduced, so existing OpenAPI stays unchanged and protected routes remain denied.

## Transactions and concurrency
Database constraints protect concurrent starts across replicas. Completion locks only the owned journey row and commits the transition in one transaction. Retry start must never reactivate a completed journey. Transactions perform no network/provider calls. Lists use keyset pagination over (started_at, id), owner-filtered, limit 1–50, with an owner/timestamp/ID index.

## Configuration and migration
Default preview continues without database configuration. An explicit persistence profile enables a bounded Hikari pool and Flyway migrations, with URL/user/password supplied externally. No production credential defaults. Migration failure fails startup. Migration creates new tables/indexes only; no existing data is dropped. Existing identity table/FK and retention/deletion workflow remain prerequisites before public writes.

## Tests and acceptance
Use an isolated PostgreSQL 16 Testcontainers database, not the user's Compose data. Docker absence fails the integration suite rather than silently skipping it. Verify migration repeatability/constraints, start/completion replay, conflict detection, ownership, keyset pagination and concurrent writers using separate store instances/connections. Confirm preview HTTP security tests still pass with database configuration absent. No live UI feature or production readiness is claimed by this work.

## Deferred
Auth/session provider choice, protected contracts/controllers, durable client outbox/sync, owner-deletion lifecycle, public rollout, presence and Mapbox. No UI, new AI provider or realtime event publication in this slice.
