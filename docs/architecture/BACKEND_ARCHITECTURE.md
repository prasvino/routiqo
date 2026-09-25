# Backend architecture

Journey, Spots and Ask Ahead (see [`../PRODUCT.md`](../PRODUCT.md) and
[`ENGINEERING_CONTEXT.md`](ENGINEERING_CONTEXT.md)): planned core-api domains
`spot` (seeded Spot catalog, posts, signals, voice-note metadata, "Still true?",
per-type expiry, highlights), `askahead` (questions, bounded non-deterministic
recipient selection, answers), `room` (short temporary Spot chat, festival route
room, per-room aliases) and `routeguide`. They reuse the existing signal storage,
idempotent commands, abuse budgets, expiry maintenance, anchor catalog and
route-anchor matching in `routeupdate`, and journey ownership through application
interfaces. Expiry uses server time. Spot passage is opt-in, received only with an
answer or for Ask Ahead eligibility, stored with coarse time and purged within
24 hours. No server-side location ingestion or presence. Chat/room transport is
bounded HTTP refresh ([ADR 0066](../adr/0066-bounded-http-refresh-for-spot-chat.md)).

Already-built private owner consent, route binding and Quick Signal command
storage/transports (ADRs 0026–0035) and public LIVE publication (`publiclive`)
are archived designs; their code stays behind default-off gates.

ADR 0036 specifies opt-in expiry maintenance beside the domain-owned core-api
persistence composition. It calls application cleanup interfaces in independent
bounded transactions with its own scheduler; the workers application does not
duplicate core repositories. Logical expiry remains independent of job execution.
The same pattern is the planned home for purging expired Spot posts, voice-note
objects and Spot-passage records (24 hours).

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
development authentication bypass is allowed. Domain lifecycle and the archived
presence policy are pure Java and tested, not public live-presence APIs.
