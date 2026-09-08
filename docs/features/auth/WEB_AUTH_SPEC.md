# Browser authentication transport

Opt-in web-auth profile requires persistence + google-auth. Default preview continues to deny auth paths. No deployment or real Google login is performed by this slice.

Routes under /api/v1/auth: GET csrf, POST google/challenge, POST google/exchange, GET session, POST session/renew, POST account/delete, POST logout. The challenge response contains id/nonce/expiry but never its binding. Exchange accepts challengeId/idToken and returns accountId/expiry but never session credentials. Binding and session use host-only HttpOnly SameSite=Strict cookies, Secure by default, Path=/. Explicit insecure cookies are allowed only with a loopback HTTP origin for local development. No Domain cookie attribute. HTTPS uses __Host- cookie names; explicit local HTTP uses unprefixed names.

Spring cookie CSRF repository uses a separate HttpOnly cookie and masked token returned by GET csrf; client sends X-XSRF-TOKEN on every POST. Require an exact configured Origin on POST, reject cross-site fetch metadata and never enable wildcard CORS. Responses are no-store. Browser clients must use a same-origin API proxy; native transport is deferred. Credentials never enter localStorage, JSON responses or URL parameters.

All auth requests use a PostgreSQL fixed one-minute rate bucket keyed by HMAC of socket peer address and route class; forwarded headers are ignored. Configured secret key is mandatory and shared across replicas. Challenge limit 10/minute, exchange 20, others 120; return 429 with Retry-After. SQL atomically arbitrates counters. Behind a proxy this conservatively shares its limit; trusted client-address forwarding needs an explicit deployment design. Limit JSON bodies to 20 KiB before deserialization, reject unsupported content types.

A scheduled bounded cleanup deletes expired challenge/session/rate rows in batches of 100 every five minutes, using indexed key selection and SKIP LOCKED. Monitor cleanup backlog and tune capacity before deployment; bounded batches alone do not guarantee throughput. Session lineage hashes remain until 12 hours after original authentication, then bounded cleanup removes them; this preserves logout across rotation races. Bounded renewal and recent-auth account deletion are implemented; live deployment review remains pending. See their dedicated specs and ADR 0011.

Acceptance: real HTTP + disposable PostgreSQL tests exercise CSRF bootstrap, rejected origins/missing CSRF, cookie flags, challenge/exchange/session/logout, replay, no body credential exposure, body limits and rate limiting. Google verifier is replaced only in test configuration; production verifier remains mandatory. OpenAPI generated models must match these routes.

