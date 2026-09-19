# Backend architecture

Routiqo Live planning: ADR 0022 selects core-domain evidence ingestion and an
authorized moment projection. Quick Signals reuse structured Route Updates;
journey ownership and presence consent are accessed through application interfaces.
The first release uses bounded HTTP, not a new chat service or gateway dependency.
Private owner consent, route binding and Quick Signal command storage/transports
are implemented behind default-off gates (ADRs 0026–0035). Public cohort
publication remains gated by `docs/features/live/ROUTIQO_LIVE_SPEC.md`.

ADR 0036 specifies opt-in expiry maintenance beside the domain-owned core-api
persistence composition. It calls application cleanup interfaces in independent
bounded transactions with its own scheduler; the workers application does not
duplicate core repositories. Logical expiry remains independent of job execution.

ADR 0041 replaces unaudited contribution mutation with an internal moderation
command using the identity-owned ordered account-pair transaction, finite scoped
operator grants, exact subject revision and atomic audit/debit persistence. Signal
ingestion consumes a read-only restriction interface. Neither the service nor its
grant/audit participants are API dependencies. Its bounded cleanup adapters are
callable only; they are not added to the expiry scheduler in this phase.
Administrative authentication, grant administration and operational case handling
remain unimplemented. The grant table starts empty.

Java 25, Spring Boot, Gradle Kotlin DSL. Domains own api/application/domain/infrastructure code. Controllers do not manipulate repositories. Core, realtime and workers build independently.
The default foundation exposes public health/catalog and denies protected access.
Opt-in identity/persistence profiles implement authenticated journey APIs; no
development authentication bypass is allowed. Domain lifecycle and presence policy
are pure Java and tested, not public live-presence APIs.
