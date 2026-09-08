# 0005 — Durable journey lifecycle foundation

Use Spring JDBC and explicit transactions for the first journey lifecycle persistence adapter, PostgreSQL constraints for concurrency, Flyway for migrations, and Testcontainers PostgreSQL for integration verification. This follows the existing relational-store decision without introducing another service or datastore.

Keep the default discovery preview database-independent. Configure a persistence profile with external credentials; no protected controller is enabled. Repository ownership checks are defense in depth, not authentication. Identity FK, retention/deletion and authenticated API/sync design remain launch prerequisites.

One active journey per owner matches the singular active-journey surface; enforce it in PostgreSQL rather than application memory. A journey UUID is the retry identity for start; start replay returns the durable record and cannot reactivate it. Ordered owner-filtered keyset pages avoid unbounded reads.

Alternatives: JPA is unnecessary for this small explicit SQL boundary; process-local storage cannot satisfy restart/multi-replica correctness; exposing endpoints before identity would violate the guardrails.

Dependencies use the Spring Boot 4.1.1 managed BOM. Reference: [Spring database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html), [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/). No application data goes to external services; test containers are local and disposable.
