# Backend architecture

Routiqo Live planning: ADR 0022 selects core-domain evidence ingestion and an
authorized moment projection. Quick Signals reuse structured Route Updates;
journey ownership and presence consent are accessed through application interfaces.
The first release uses bounded HTTP, not a new chat service or gateway dependency.
Before migrations/endpoints, settle cohort admission and evidence lifecycle as
specified in `docs/features/live/ROUTIQO_LIVE_SPEC.md`. No Live runtime exists yet.

Java 25, Spring Boot, Gradle Kotlin DSL. Domains own api/application/domain/infrastructure code. Controllers do not manipulate repositories. Core, realtime and workers build independently.
The default foundation exposes public health/catalog and denies protected access.
Opt-in identity/persistence profiles implement authenticated journey APIs; no
development authentication bypass is allowed. Domain lifecycle and presence policy
are pure Java and tested, not public live-presence APIs.
