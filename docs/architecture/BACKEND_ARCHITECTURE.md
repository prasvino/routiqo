# Backend architecture
Java 25, Spring Boot, Gradle Kotlin DSL. Domains own api/application/domain/infrastructure code. Controllers do not manipulate repositories. Core, realtime and workers build independently.
Foundation exposes only public service health/catalog routes. Everything else is denied by Spring Security; no development authentication bypass.
Domain lifecycle and presence policy are pure Java and tested. They are not public live-presence APIs. Persistence and auth arrive before protected writes.

