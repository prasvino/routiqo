# 0009 — Opt-in browser auth transport

Expose browser authentication only with web-auth plus persistence/google-auth. Use a dedicated Spring Security chain for exact auth routes; all other protected paths retain the existing deny policy. No bearer-token acceptance is added to journey APIs.

Use HttpOnly Secure SameSite=Strict host-only cookies with __Host- names in HTTPS mode. Local HTTP requires an explicit loopback origin and insecure-cookie setting, using unprefixed development names. Obtain the masked CSRF token from a no-store same-origin endpoint; retain Spring's CSRF enforcement and require exact POST Origin. No permissive CORS. Use only the socket peer for rate keys until trusted proxy deployment is designed.

PostgreSQL atomically enforces one-minute request counters across replicas. HMAC masks peer addresses using an externally supplied shared secret. Bounded 20 KiB buffering precedes JSON deserialization. Limits are conservative per-peer defaults; proxy aggregation, capacity and cleanup backlog must be evaluated before deployment.

An indexed scheduled cleanup deletes up to 100 expired rows per table every five minutes. SKIP LOCKED supports multiple replicas. Batches bound request/maintenance cost; they do not prove cleanup throughput under production load.

The HTTP tests use real PostgreSQL and real CSRF/cookie processing. Only the Google verifier is replaced in test configuration; real signature tests remain separate. Public OAuth registration and real browser/native sign-in remain unverified.

Reference: https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html . No new dependency or external service was added.
